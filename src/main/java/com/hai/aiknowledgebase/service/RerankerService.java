package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.annotation.Timed;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.LongBuffer;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Cross-Encoder Rerank 服务
 *
 * <h2>功能概述</h2>
 * 使用 BGE-Reranker-v2-m3（ONNX 格式）对检索结果进行精排。
 * Cross-Encoder 将 (query, doc) 对联合编码，捕捉 Bi-Encoder 无法建模的词间交互，
 * 显著提升 Top-K 精度（典型 MRR 提升 5~15%）。
 *
 * <h2>模型要求</h2>
 * <ol>
 *   <li>从 HuggingFace 下载 BAAI/bge-reranker-v2-m3 的 ONNX 模型文件</li>
 *   <li>将 model.onnx 和 tokenizer.json 放到 {@code reranker.model-path} 指定目录</li>
 * </ol>
 *
 * <h2>使用流程</h2>
 * <pre>
 *   List&lt;String&gt; docs = ...;  // 候选文档片段
 *   List&lt;RerankResult&gt; reranked = rerankerService.rerank(query, docs, topN);
 * </pre>
 */
@Slf4j
@Service
public class RerankerService {

    /** Rerank 总开关 */
    @Value("${reranker.enabled:false}")
    private boolean enabled;

    /** ONNX 模型目录路径（包含 model.onnx 和 tokenizer.json） */
    @Value("${reranker.model-path:models/bge-reranker-v2-m3}")
    private String modelPath;

    /** Rerank 超时时间（毫秒），CPU 推理 batch 模式通常需要 2~8 秒 */
    @Value("${reranker.timeout-ms:10000}")
    private long timeoutMs;

    /** 单次 Rerank 最大文档数（防止超长输入导致 OOM） */
    @Value("${reranker.max-docs:30}")
    private int maxDocs;

    /** 模型最大序列长度 */
    @Value("${reranker.max-seq-length:512}")
    private int maxSeqLength;

    /** GPU 设备 ID（-1 表示使用 CPU，>=0 表示使用指定 GPU） */
    @Value("${reranker.gpu-device-id:-1}")
    private int gpuDeviceId;

    /** Rerank 最低分数阈值，低于此分视为不相关 */
    @Value("${reranker.min-score:-3.5}")
    private double minScore;

    private HuggingFaceTokenizer tokenizer;
    private OrtEnvironment ortEnv;
    private OrtSession session;
    private ExecutorService rerankExecutor;
    private volatile boolean initialized = false;

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("Reranker 已禁用 (reranker.enabled=false)");
            return;
        }

        try {
            log.info("初始化 BGE-Reranker，模型路径: {}", modelPath);

            Path tokenizerPath = Paths.get(modelPath, "tokenizer.json");
            tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerPath)
                    .optMaxLength(maxSeqLength)
                    .optTruncation(true)
                    .optPadding(true)
                    .build();
            log.info("Tokenizer 加载完成，maxLength={}", maxSeqLength);

            ortEnv = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();

            // 1. 启用所有优化（常量折叠、图优化、内存复用等）
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);

            // 2. 启用 CPU 内存池（Arena），减少内存碎片（默认即启用，显式设置也无妨）
            opts.setCPUArenaAllocator(true);

            // 3. 设置执行模式为并行（默认即并行，可省略）
            opts.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.PARALLEL);

            // 4. 设置线程数（对 CPU 推理有效，GPU 下影响较小）
            opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());

            if (gpuDeviceId >= 0) {
                try {
                    opts.addCUDA(gpuDeviceId);
                    log.info("已启用 CUDA 执行提供程序，GPU 设备 ID: {}", gpuDeviceId);
                } catch (OrtException e) {
                    log.warn("CUDA 不可用，降级为 CPU 推理: {}", e.getMessage());
                    opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());
                }
            } else {
                opts.setIntraOpNumThreads(Runtime.getRuntime().availableProcessors());
                log.info("使用 CPU 推理，线程数: {}", Runtime.getRuntime().availableProcessors());
            }
            String onnxModelPath = Paths.get(modelPath, "model.onnx").toString();
            session = ortEnv.createSession(onnxModelPath, opts);

            rerankExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "reranker-worker");
                t.setDaemon(true);
                return t;
            });

            initialized = true;
            log.info("BGE-Reranker 初始化完成");

            if (gpuDeviceId >= 0) {
                try {
                    warmUp();
                } catch (Exception e) {
                    log.warn("GPU 预热失败（不影响使用）: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.warn("BGE-Reranker 初始化失败，Rerank 将不可用: {}", e.getMessage());
            initialized = false;
        }
    }

    private volatile boolean cleaned = false;

    @PreDestroy
    void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;

        if (rerankExecutor != null) {
            rerankExecutor.shutdownNow();
            try {
                rerankExecutor.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            rerankExecutor = null;
        }

        // 不手动关闭 session / tokenizer / ortEnv：
        // ONNX Runtime + CUDA 在 JVM 关闭时存在 native 崩溃问题
        // (EXCEPTION_ACCESS_VIOLATION)，因为 CUDA context 在 ShutdownHook
        // 阶段已被销毁，此时 close() 会访问已释放的 GPU 内存。
        // JVM 进程退出时操作系统会自动回收所有 native 资源，无需手动释放。
        initialized = false;
        log.info("Reranker 资源标记为已释放（native 资源由 OS 自动回收）");
    }

    /**
     * GPU 预热：执行一次空推理，触发 CUDA kernel 编译，避免首次请求超时
     */
    private void warmUp() {
        long start = System.currentTimeMillis();
        try {
            List<String> warmupDocs = List.of("预热文本");
            doRerank("预热查询", warmupDocs);
            log.info("GPU 预热完成，耗时: {}ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("GPU 预热异常: {}", e.getMessage());
        }
    }

    /**
     * Rerank 结果包装类
     */
    public static class RerankResult {
        /** 文档在原始列表中的索引 */
        private final int index;
        /** 文档文本 */
        private final String text;
        /** Rerank 分数（Cross-Encoder 输出的相关性分数） */
        private final double score;

        public RerankResult(int index, String text, double score) {
            this.index = index;
            this.text = text;
            this.score = score;
        }

        public int getIndex() { return index; }
        public String getText() { return text; }
        public double getScore() { return score; }
    }

    /**
     * 对候选文档进行 Rerank 精排
     *
     * @param query 用户查询
     * @param docs  候选文档列表
     * @param topN  返回前 N 个结果
     * @return 按分数降序排列的 Rerank 结果
     */
    @Timed("Rerank 精排")
    public List<RerankResult> rerank(String query, List<String> docs, int topN) {
        if (!initialized) {
            log.debug("Reranker 未初始化，跳过精排");
            return fallbackResults(docs, topN);
        }

        if (docs == null || docs.isEmpty()) {
            return List.of();
        }

        // 限制候选数量
        List<String> candidateDocs = docs.size() > maxDocs
                ? docs.subList(0, maxDocs)
                : docs;

        try {
            List<RerankResult> results = rerankWithTimeout(query, candidateDocs);
            results.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
            return results.size() <= topN ? results : results.subList(0, topN);
        } catch (Exception e) {
            log.warn("Rerank 执行失败，降级返回原始顺序: {}", e.getMessage());
            return fallbackResults(candidateDocs, topN);
        }
    }

    /**
     * 带超时的 Rerank 执行
     */
    private List<RerankResult> rerankWithTimeout(String query, List<String> docs) {
        CompletableFuture<List<RerankResult>> future = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return doRerank(query, docs);
                    } catch (Exception e) {
                        log.warn("Rerank 推理失败，已降级返回原始顺序: {}", e.getMessage());
                        throw new RuntimeException("Rerank 推理失败", e);
                    }
                }, rerankExecutor);
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("Rerank 超时 ({}ms)，已取消", timeoutMs);
            throw new RuntimeException("Rerank 超时", e);
        } catch (Exception e) {
            throw new RuntimeException("Rerank 异常", e);
        }
    }

    /**
     * 执行 Cross-Encoder Batch 推理
     *
     * <p>将所有 (query, doc) 对打包为 [batch_size, max_seq_len] 张量，
     * 一次 ONNX 推理获得所有相关性分数，避免串行循环导致的超时。</p>
     *
     * <ol>
     *   <li>对每个 pair 独立 tokenize，记录各自长度</li>
     *   <li>取最大长度作为 batch 的统一序列长度，短序列右侧补零</li>
     *   <li>构建 [batch_size, max_seq_len] 的 input_ids 和 attention_mask 张量</li>
     *   <li>一次 ONNX 推理 → [batch_size, 1] logits</li>
     *   <li>拆分 logits 为各文档的相关性分数</li>
     * </ol>
     */
    private List<RerankResult> doRerank(String query, List<String> docs) throws Exception {
        long t0 = System.currentTimeMillis();
        int batchSize = docs.size();

        // Step 1: 逐个 tokenize，记录各序列长度
        long[][] allInputIds = new long[batchSize][];
        long[][] allAttentionMasks = new long[batchSize][];
        int maxLen = 0;

        for (int i = 0; i < batchSize; i++) {
            var encoding = tokenizer.encode(query, docs.get(i), true, false);
            allInputIds[i] = encoding.getIds();
            allAttentionMasks[i] = encoding.getAttentionMask();
            maxLen = Math.max(maxLen, allInputIds[i].length);
        }
        long t1 = System.currentTimeMillis();

        // Step 2: 将所有序列 padding 到 maxLen，展平为 1D 数组
        long[] flatInputIds = new long[batchSize * maxLen];
        long[] flatAttentionMask = new long[batchSize * maxLen];

        for (int i = 0; i < batchSize; i++) {
            int offset = i * maxLen;
            System.arraycopy(allInputIds[i], 0, flatInputIds, offset, allInputIds[i].length);
            System.arraycopy(allAttentionMasks[i], 0, flatAttentionMask, offset, allAttentionMasks[i].length);
        }

        // Step 3: 构建 batch 张量，一次推理
        long[] shape = {batchSize, maxLen};
        OnnxTensor inputIdsTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(flatInputIds), shape);
        OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(ortEnv, LongBuffer.wrap(flatAttentionMask), shape);

        try {
            Map<String, OnnxTensor> inputs = new LinkedHashMap<>();
            inputs.put("input_ids", inputIdsTensor);
            inputs.put("attention_mask", attentionMaskTensor);

            OrtSession.Result output = session.run(inputs);
            long t2 = System.currentTimeMillis();
            float[][] logits = (float[][]) output.get(0).getValue();

            List<RerankResult> results = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                results.add(new RerankResult(i, docs.get(i), logits[i][0]));
            }
            output.close();

        log.info("Rerank batch 推理完成: batchSize={}, maxLen={}, tokenize={}ms, inference={}ms, total={}ms",
                    batchSize, maxLen, t1 - t0, t2 - t1, t2 - t0);
            return results;
        } finally {
            inputIdsTensor.close();
            attentionMaskTensor.close();
        }
    }
    /**
     * 降级结果：保持原始顺序，分数设为 0
     */
    private List<RerankResult> fallbackResults(List<String> docs, int topN) {
        List<RerankResult> results = new ArrayList<>();
        int limit = Math.min(docs.size(), topN);
        for (int i = 0; i < limit; i++) {
            results.add(new RerankResult(i, docs.get(i), 0.0));
        }
        return results;
    }

    /**
     * Reranker 是否可用
     */
    public boolean isAvailable() {
        return initialized;
    }

    /**
     * Reranker 是否启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Rerank 最低分数阈值
     */
    public double getMinScore() {
        return minScore;
    }
}