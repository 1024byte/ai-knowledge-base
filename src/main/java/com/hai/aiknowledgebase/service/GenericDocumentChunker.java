package com.hai.aiknowledgebase.service;

import com.hai.aiknowledgebase.common.SplitStrategy;
import com.hai.aiknowledgebase.common.TextTokenUtils;
import dev.langchain4j.model.TokenCountEstimator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <h2>通用文档切片器</h2>
 *
 * <p>提供四种无内容感知的通用切分策略：递归字符 / 按句子 / 按段落 / 按行。</p>
 *
 * <p><b>重叠预留</b>：切片阶段使用 {@code maxTokens - overlapTokens} 作为目标上限，
 * 为后续添加重叠预留空间，避免 chunk 切满后无法加重叠。</p>
 */
@Slf4j
@Component
public class GenericDocumentChunker {

    /** 句子边界正则：中英文句末标点（不含分号），切分时保留标点 */
    private static final Pattern SENTENCE_PATTERN =
            Pattern.compile("[^。！？!?]+[。！？!?]?");

    /** 有意义的重叠最小 token 阈值 */
    private static final int MIN_MEANINGFUL_OVERLAP = 10;

    /** 硬切时最大 token 的下限保护，防止死循环 */
    private static final int MIN_HARD_SPLIT_TOKENS = 4;

    private final TokenCountEstimator tokenEstimator;

    @Value("${document.chunk.min-tokens:200}")
    private int minTokens;

    @Value("${document.chunk.max-tokens:800}")
    private int maxTokens;

    public GenericDocumentChunker(TokenCountEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 按指定策略对文本进行通用切分。
     */
    public List<MarkdownDocumentChunker.Chunk> chunk(String content, SplitStrategy strategy,
                                                     Integer chunkLength, Integer overlapLength) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        int configMaxTokens = (chunkLength != null && chunkLength > 0) ? chunkLength : maxTokens;
        int effectiveOverlapTokens = (overlapLength != null && overlapLength > 0) ? overlapLength : 0;

        // 关键：切片阶段目标 = maxTokens - overlapTokens，为重叠预留空间
        int reserved = Math.max(0, effectiveOverlapTokens);
        int candidate = configMaxTokens - reserved;
        // 保底：不能小于 minTokens；若配置不合理（overlap >= max），退化为 max 的一半
        int effectiveMinTokens = Math.min(minTokens, configMaxTokens);
        if (candidate < effectiveMinTokens) {
            candidate = Math.max(effectiveMinTokens, configMaxTokens / 2);
        }
        int effectiveMaxTokens = Math.min(candidate, configMaxTokens);

        log.debug("GenericDocumentChunker: max={}, overlap={}, effectiveMax={}",
                configMaxTokens, effectiveOverlapTokens, effectiveMaxTokens);

        List<String> units = splitIntoUnits(content, strategy, effectiveMaxTokens);
        List<MarkdownDocumentChunker.Chunk> chunks = assemble(units, effectiveMaxTokens);
        chunks = mergeSmallChunks(chunks, effectiveMinTokens, effectiveMaxTokens);
        return applyOverlap(chunks, effectiveOverlapTokens, configMaxTokens);
    }

    // ======================== 原子块切分 ========================

    private List<String> splitIntoUnits(String content, SplitStrategy strategy, int maxTokens) {
        switch (strategy) {
            case LINE:
                return Arrays.stream(content.split("\\n", -1))
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            case PARAGRAPH:
                return Arrays.stream(content.split("\\n\\s*\\n"))
                        .map(String::strip)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            case SENTENCE:
                return splitSentences(content);
            case RECURSIVE:
                return splitRecursive(content, maxTokens);
            default:
                return List.of();
        }
    }

    private List<String> splitSentences(String content) {
        List<String> sentences = new ArrayList<>();
        Matcher matcher = SENTENCE_PATTERN.matcher(content);
        while (matcher.find()) {
            String sentence = matcher.group().strip();
            if (!sentence.isEmpty()) {
                sentences.add(sentence);
            }
        }
        return sentences;
    }

    private List<String> splitRecursive(String content, int maxTokens) {
        List<String> result = new ArrayList<>();
        for (String paragraph : content.split("\\n\\s*\\n")) {
            String p = paragraph.strip();
            if (p.isEmpty()) continue;
            if (tokenEstimator.estimateTokenCountInText(p) <= maxTokens) {
                result.add(p);
                continue;
            }
            for (String sentence : splitSentences(p)) {
                if (tokenEstimator.estimateTokenCountInText(sentence) <= maxTokens) {
                    result.add(sentence);
                } else {
                    result.addAll(splitPhrases(sentence));
                }
            }
        }
        return result;
    }

    /**
     * 按词边界（逗号/顿号/冒号/空白）切分为短语。
     *
     * <p>修复：过短碎片（&lt; 2 字符）合并到前一个短语，避免英文标点切出
     * 大量 1 字符原子块导致语义破碎。</p>
     */
    private List<String> splitPhrases(String text) {
        List<String> phrases = new ArrayList<>();
        for (String part : text.split("[，,、:：]+")) {
            String p = part.strip();
            if (p.isEmpty()) continue;
            for (String word : p.split("\\s+")) {
                String w = word.strip();
                if (w.isEmpty()) continue;
                // 过短碎片（如英文 "a"、"I"）合并到前一个，保持语义
                if (w.length() < 2 && !phrases.isEmpty()) {
                    phrases.set(phrases.size() - 1, phrases.get(phrases.size() - 1) + " " + w);
                } else {
                    phrases.add(w);
                }
            }
        }
        return phrases;
    }

    // ======================== token 约束组装 ========================

    /**
     * 组装原子块为 chunk。
     *
     * <p>修复：bufferTokens 用累加方式而非每次重算整个 buffer，避免 O(n²) 和估算漂移。</p>
     */
    private List<MarkdownDocumentChunker.Chunk> assemble(List<String> units, int maxTokens) {
        List<MarkdownDocumentChunker.Chunk> chunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;
        boolean bufferHasContent = false;

        for (String unit : units) {
            int unitTokens = tokenEstimator.estimateTokenCountInText(unit);
            if (unitTokens > maxTokens) {
                // 单个原子块超长：先冲刷 buffer，再硬切
                if (bufferHasContent) {
                    chunks.add(buildChunk(buffer.toString()));
                    buffer.setLength(0);
                    bufferTokens = 0;
                    bufferHasContent = false;
                }
                chunks.addAll(hardSplitByTokens(unit, maxTokens));
                continue;
            }
            // +1 为段落间换行 token 的预估
            if (bufferTokens + unitTokens + 1 <= maxTokens) {
                if (bufferHasContent) {
                    buffer.append('\n');
                }
                buffer.append(unit);
                bufferTokens += unitTokens + 1;
                bufferHasContent = true;
            } else {
                chunks.add(buildChunk(buffer.toString()));
                buffer.setLength(0);
                buffer.append(unit);
                bufferTokens = unitTokens + 1;
                bufferHasContent = true;
            }
        }
        if (bufferHasContent) {
            chunks.add(buildChunk(buffer.toString()));
        }
        return chunks;
    }

    /**
     * 超长文本按真实 token 粒度硬切（二分定位切点，确保每个 Chunk token ≤ maxTokens）。
     *
     * <p>修复：增加 end &gt; start 保护，防止极端情况下（如单字符 token 数 &gt; maxTokens）死循环。</p>
     */
    private List<MarkdownDocumentChunker.Chunk> hardSplitByTokens(String text, int maxTokens) {
        List<MarkdownDocumentChunker.Chunk> result = new ArrayList<>();
        // 保底：maxTokens 至少 MIN_HARD_SPLIT_TOKENS，避免切出 1 字符死循环
        int safeMax = Math.max(maxTokens, MIN_HARD_SPLIT_TOKENS);
        int start = 0;
        int length = text.length();
        while (start < length) {
            int low = start + 1;
            int high = length;
            int end = start + 1;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                if (tokenEstimator.estimateTokenCountInText(text.substring(start, mid)) <= safeMax) {
                    end = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            // 保护：若二分没推进，强制前进 1 字符，避免死循环
            if (end <= start) {
                end = start + 1;
            }
            result.add(buildChunk(text.substring(start, end)));
            start = end;
        }
        return result;
    }

    /**
     * 合并低于 minTokens 的小片段。
     *
     * <p>修复：合并后实际重算 token，超 maxTokens 则放弃合并，避免 BPE 边界效应导致超限。</p>
     */
    private List<MarkdownDocumentChunker.Chunk> mergeSmallChunks(List<MarkdownDocumentChunker.Chunk> chunks,
                                                                 int minTokens, int maxTokens) {
        if (chunks.size() <= 1) {
            return chunks;
        }
        List<MarkdownDocumentChunker.Chunk> result = new ArrayList<>();
        MarkdownDocumentChunker.Chunk pending = null;
        for (MarkdownDocumentChunker.Chunk chunk : chunks) {
            if (pending == null) {
                pending = chunk;
                continue;
            }
            if (pending.tokenCount() < minTokens) {
                // 合并后重算实际 token
                String combined = pending.text() + "\n" + chunk.text();
                int combinedTokens = tokenEstimator.estimateTokenCountInText(combined);
                if (combinedTokens <= maxTokens) {
                    pending = new MarkdownDocumentChunker.Chunk(
                            combined, combinedTokens, pending.contextPrefix(), pending.metadata());
                    continue;
                }
            }
            result.add(pending);
            pending = chunk;
        }
        if (pending != null) {
            result.add(pending);
        }
        return result;
    }

    // ======================== 重叠处理 ========================

    /**
     * <h3>在相邻 Chunk 之间添加重叠内容</h3>
     *
     * <p>修复：</p>
     * <ul>
     *   <li>切片阶段已用 {@code maxTokens - overlapTokens} 预留空间，
     *       此处 {@code maxAllowedOverlap} 应约等于 overlapTokens</li>
     *   <li>拼接统一用 {@code \n\n}</li>
     *   <li>加可见性日志</li>
     * </ul>
     */
    private List<MarkdownDocumentChunker.Chunk> applyOverlap(List<MarkdownDocumentChunker.Chunk> chunks,
                                                             int overlapTokens, int maxTokens) {
        if (chunks.size() <= 1 || overlapTokens <= 0) {
            return chunks;
        }
        List<MarkdownDocumentChunker.Chunk> result = new ArrayList<>();
        result.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            MarkdownDocumentChunker.Chunk prev = chunks.get(i - 1);
            MarkdownDocumentChunker.Chunk curr = chunks.get(i);

            int maxAllowedOverlap = maxTokens - curr.tokenCount();
            if (maxAllowedOverlap <= 0) {
                log.debug("[overlap] generic chunk#{} 已满 (tokens={}, max={})，跳过重叠",
                        i, curr.tokenCount(), maxTokens);
                result.add(curr);
                continue;
            }
            int actualTarget = Math.min(overlapTokens, maxAllowedOverlap);
            if (actualTarget < overlapTokens) {
                log.warn("[overlap] generic chunk#{} 空间不足，期望={}, 实际={} (currTokens={}, max={})",
                        i, overlapTokens, actualTarget, curr.tokenCount(), maxTokens);
            }

            String overlapText = extractTailByText(prev.text(), actualTarget);

            if (overlapText.isEmpty() && actualTarget >= MIN_MEANINGFUL_OVERLAP) {
                log.warn("[overlap] generic chunk#{} 期望={} tokens，但提取结果为空", i, actualTarget);
            }

            if (!overlapText.isEmpty()) {
                String newText = overlapText + "\n\n" + curr.text();
                int newTokens = tokenEstimator.estimateTokenCountInText(newText);
                log.debug("[overlap] generic chunk#{} 期望={}, 实际增加={} tokens",
                        i, actualTarget, newTokens - curr.tokenCount());
                result.add(new MarkdownDocumentChunker.Chunk(
                        newText, newTokens, curr.contextPrefix(), curr.metadata()));
            } else {
                result.add(curr);
            }
        }
        return result;
    }

    /**
     * <h3>从文本尾部按段落提取指定 token 数量的内容</h3>
     *
     * <p>修复：超长段分支用 {@code targetTokens - tokens}（剩余预算），
     * 避免多段叠加后总重叠超 targetTokens。</p>
     */
    private String extractTailByText(String text, int targetTokens) {
        if (text == null || text.isEmpty() || targetTokens <= 0) {
            return "";
        }
        String[] paragraphs = text.split(
                "\\n\\s*\\n|\\n\\s*---\\s*\\n|\\n\\s*\\*\\*\\*\\s*\\n");

        StringBuilder tail = new StringBuilder();
        int tokens = 0;

        for (int i = paragraphs.length - 1; i >= 0; i--) {
            String para = paragraphs[i].strip();
            if (para.isEmpty()) continue;

            int paraTokens = tokenEstimator.estimateTokenCountInText(para);

            // 单段超预算：用剩余预算截断，不再整段带入
            if (paraTokens > targetTokens) {
                int remain = targetTokens - tokens;
                if (remain <= 0) break;
                String truncated = TextTokenUtils.truncateTailByTokens(
                        para, remain, tokenEstimator);
                if (!truncated.isEmpty()) {
                    if (tail.length() == 0) {
                        tail.insert(0, truncated);
                    } else {
                        tail.insert(0, truncated + "\n\n");
                    }
                }
                break;
            }

            if (tail.length() > 0 && tokens + paraTokens > targetTokens) {
                break;
            }

            if (tail.length() == 0) {
                tail.insert(0, para);
            } else {
                tail.insert(0, para + "\n\n");
            }
            tokens += paraTokens;
        }
        return tail.toString().strip();
    }

    private MarkdownDocumentChunker.Chunk buildChunk(String text) {
        int tokens = tokenEstimator.estimateTokenCountInText(text);
        return new MarkdownDocumentChunker.Chunk(text, tokens, "");
    }
}