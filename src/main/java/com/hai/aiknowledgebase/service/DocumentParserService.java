package com.hai.aiknowledgebase.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hai.aiknowledgebase.common.CustomDocument;
import com.hai.aiknowledgebase.common.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static com.hai.aiknowledgebase.common.FileUtils.getFileExtension;

/**
 * <h3>文档解析服务 —— 多引擎文档内容提取</h3>
 *
 * <p>将各种格式的原始文档（PDF、Word、PPT、Excel、图片、Markdown、纯文本）
 * 统一解析为纯文本/Markdown 内容，供后续向量化使用。</p>
 *
 * <h4>架构设计</h4>
 * <pre>{@code
 * 文件扩展名路由:
 *   md / txt           → Java NIO 直接读取（零开销，最快）
 *   docx               → python-docx 脚本（轻量 Office 解析）
 *   pdf / pptx / xlsx /
 *   doc / ppt / xls /
 *   jpg / png / ...    → MinerU 深度学习引擎（OCR + 版面分析 + 表格识别）
 *   未知格式            → MinerU 兜底尝试
 * }</pre>
 *
 * <h4>为什么分层选择引擎？</h4>
 * <ul>
 *   <li><b>纯文本直读</b>：md/txt 本身就是文本，走 Python 进程反而增加 IPC 开销</li>
 *   <li><b>python-docx</b>：docx 是 Office Open XML 格式，python-docx 库比 MinerU 更快更轻量</li>
 *   <li><b>MinerU</b>：处理 PDF 扫描件、图片 OCR、复杂表格等需要深度学习模型的重型任务</li>
 * </ul>
 *
 * @see CustomDocument 解析结果载体
 * @see FileUtils      文件工具类（扩展名提取、内容读取）
 */
@Slf4j
@Service
public class DocumentParserService {

    /** Jackson ObjectMapper 实例，用于解析 MinerU 返回的嵌套 JSON */
    private final ObjectMapper objectMapper = new ObjectMapper();

    // ==================== 配置项 ====================

    /** Python 解释器路径，默认指向 Anaconda pytorch_gpu 环境 */
    @Value("${mineru.python.path:E:/Anaconda_envs/envs/pytorch_gpu/python.exe}")
    private String pythonPath;

    /** python-docx 解析脚本路径，用于处理 .docx 文件 */
    @Value("${mineru.scripts.docx}")
    private String docxScriptPath;

    /** MinerU 解析脚本路径，用于处理 PDF、图片、PPT、Excel 等复杂文档 */
    @Value("${mineru.scripts.pdf}")
    private String pdfScriptPath;

    /** Python 进程输出最大字节数（默认 500MB），超过此限制视为异常并拒绝 */
    @Value("${document.parser.max-output-size-mb:500}")
    private int maxOutputSizeMb;

    /** Python 进程超时时间（分钟），默认 10 分钟 */
    private static final int PYTHON_TIMEOUT_MINUTES = 10;

    // ==================== 预编译正则（避免每次调用重复编译） ====================

    /** 匹配 HTML 标签，如 &lt;div&gt;、&lt;span&gt; */
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    /** 匹配中文括号注释，如 【注1】、【说明】 */
    private static final Pattern BRACKET_PATTERN = Pattern.compile("【[^】]*】");

    /** 匹配水平空白字符（空格、制表符），但不匹配换行符 */
    private static final Pattern HORIZONTAL_WHITESPACE_PATTERN = Pattern.compile("[^\\S\\n]+");

    /** 匹配 3 个及以上连续换行，合并为 2 个（一个空行） */
    private static final Pattern EXCESS_NEWLINES_PATTERN = Pattern.compile("\\n{3,}");

    // ==================== 文件类型路由表 ====================

    /** 纯文本扩展名 —— 直接 Java I/O 读取，不走 Python 进程 */
    private static final Set<String> PLAIN_TEXT_EXTS = Set.of("md", "txt");

    /** DOCX 扩展名 —— 使用 python-docx 库解析 */
    private static final Set<String> DOCX_EXTS = Set.of("docx");

    /**
     * MinerU 处理扩展名 —— 需要深度学习模型的重型文档。
     * 包括：PDF、Office 旧格式（doc/xls/ppt）、Office 新格式（pptx/xlsx）、
     * 以及常见图片格式（jpg/png/bmp/tiff）。
     */
    private static final Set<String> MINERU_EXTS = Set.of(
            "pdf", "pptx", "xlsx", "doc", "ppt", "xls",
            "jpg", "jpeg", "png", "bmp", "tiff"
    );

    // ==================== 解析入口 ====================

    /**
     * <h3>文档解析主入口 —— 根据文件扩展名路由到对应的解析引擎</h3>
     *
     * <h4>路由逻辑</h4>
     * <ol>
     *   <li>提取文件扩展名并转小写（统一大小写处理）</li>
     *   <li>匹配 {@link #PLAIN_TEXT_EXTS} → 纯文本直读</li>
     *   <li>匹配 {@link #DOCX_EXTS} → python-docx 脚本</li>
     *   <li>匹配 {@link #MINERU_EXTS} → MinerU 脚本</li>
     *   <li>都不匹配 → MinerU 兜底，格式标记为 {@link CustomDocument.Format#UNKNOWN}</li>
     * </ol>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code getFileExtension(fileName).toLowerCase()}</b>：
     *       从文件名提取扩展名（如 "报告.PDF" → "pdf"），统一转小写后进行路由匹配</li>
     *   <li><b>{@code CustomDocument.builder()}</b>：
     *       使用 Lombok Builder 模式构建结果对象，包含文件名、解析内容、原始格式三个字段</li>
     *   <li><b>{@code CustomDocument.Format.fromString(extension)}</b>：
     *       将扩展名字符串映射为枚举值（如 "pdf" → Format.PDF），未知格式则返回 UNKNOWN</li>
     * </ul>
     *
     * @param filePath 文件在磁盘上的绝对路径，用于传递给 Python 脚本
     * @param fileName 原始文件名（含扩展名），用于路由判断和结果记录
     * @return 包含解析后文本内容的 CustomDocument 对象
     * @throws Exception 解析失败时抛出异常（Python 脚本错误、文件不存在等）
     */
    public CustomDocument parseDocument(Path filePath, String fileName) throws Exception {
        String extension = getFileExtension(fileName).toLowerCase();

        if (PLAIN_TEXT_EXTS.contains(extension)) {
            // ===== 分支1：纯文本直读 =====
            // md/txt 文件本身就是文本，直接通过 Java I/O 读取，零开销
            log.info("直接读取纯文本: {}", extension);
            String content = FileUtils.loadDocumentContent(filePath.toFile(), fileName);
            return CustomDocument.builder()
                    .fileName(fileName)
                    .content(content)
                    .format(CustomDocument.Format.fromString(extension))
                    .build();

        } else if (DOCX_EXTS.contains(extension)) {
            // ===== 分支2：python-docx 解析 =====
            // docx 是 Office Open XML 格式，python-docx 库比 MinerU 更轻量、更快
            checkFileSize(filePath, extension);
            log.info("使用 python-docx 解析: {}", extension);
            String content = executePythonScript(pythonPath, docxScriptPath, filePath.toString());
            return CustomDocument.builder()
                    .fileName(fileName)
                    .content(content)
                    .format(CustomDocument.Format.fromString(extension))
                    .build();

        } else if (MINERU_EXTS.contains(extension)) {
            // ===== 分支3：MinerU 深度学习引擎 =====
            // 处理 PDF、图片 OCR、PPT、Excel 等复杂文档
            checkFileSize(filePath, extension);
            log.info("使用 MinerU 解析: {}", extension);
            String content = executePythonScript(pythonPath, pdfScriptPath, filePath.toString());
            return CustomDocument.builder()
                    .fileName(fileName)
                    .content(content)
                    .format(CustomDocument.Format.fromString(extension))
                    .build();

        } else {
            // ===== 分支4：未知格式兜底 =====
            checkFileSize(filePath, extension);
            log.warn("未知文件类型: {}, 尝试使用 MinerU", extension);
            String content = executePythonScript(pythonPath, pdfScriptPath, filePath.toString());
            return CustomDocument.builder()
                    .fileName(fileName)
                    .content(content)
                    .format(CustomDocument.Format.UNKNOWN)
                    .build();
        }
    }

    // ==================== 文件大小校验 ====================

    /**
     * 检查文件大小是否超过上限，防止超大文件导致 OOM
     *
     * <p>在调用 Python 解析脚本之前进行前置校验，尽早拒绝超大文件。</p>
     *
     * @param filePath  文件路径
     * @param extension 文件扩展名（用于日志）
     * @throws RuntimeException 文件大小超过上限时抛出
     */
    private void checkFileSize(Path filePath, String extension) {
        try {
            long fileSizeBytes = Files.size(filePath);
            long maxBytes = (long) maxOutputSizeMb * 1024 * 1024;
            if (fileSizeBytes > maxBytes) {
                throw new RuntimeException(
                        String.format("文件大小超过解析上限（%dMB），当前文件: %.1fMB，类型: %s",
                                maxOutputSizeMb, fileSizeBytes / (1024.0 * 1024.0), extension));
            }
        } catch (java.io.IOException e) {
            log.warn("无法读取文件大小: {}", filePath, e);
        }
    }

    // ==================== Python 脚本执行 ====================

    /**
     * <h3>执行 Python 解析脚本并提取 Markdown 内容</h3>
     *
     * <p>通过 Java {@link ProcessBuilder} 启动 Python 子进程，执行文档解析脚本，
     * 从返回的嵌套 JSON 中提取最终 Markdown 文本。</p>
     *
     * <h4>执行流程</h4>
     * <ol>
     *   <li><b>构建命令</b>：{@code python.exe script.py /path/to/file.pdf}</li>
     *   <li><b>启动子进程</b>：通过 ProcessBuilder 创建独立进程</li>
     *   <li><b>读取输出</b>：逐行读取 stdout，拼接到 StringBuilder</li>
     *   <li><b>等待完成</b>：阻塞等待子进程退出，检查退出码</li>
     *   <li><b>JSON 提取</b>：从输出中定位 JSON 片段（去除可能的日志前缀）</li>
     *   <li><b>嵌套解析</b>：解析两层 JSON 结构，提取最终 Markdown 内容</li>
     *   <li><b>后处理清洗</b>：去除 HTML 标签、中文括号注释、合并空白</li>
     * </ol>
     *
     * <h4>MinerU 返回的嵌套 JSON 结构</h4>
     * <pre>{@code
     * 外层 JSON:
     * {
     *   "success": true,
     *   "content": "{                          ← 这是一个 JSON 字符串！需要二次解析
     *     \"results\": {
     *       \"文档名.pdf\": {                   ← 动态 key，文件名
     *         \"md_content\": \"# 标题\\n正文...\" ← 最终需要的 Markdown 文本
     *       }
     *     }
     *   }"
     * }
     * }</pre>
     *
     * <h4>关键代码解释</h4>
     * <ul>
     *   <li><b>{@code pb.redirectErrorStream(true)}</b>：
     *       将 stderr 合并到 stdout。如果不合并，当 stderr 缓冲区满时 Python 进程会阻塞，
     *       而 Java 只读 stdout 会导致<b>死锁</b>。这是 ProcessBuilder 的经典陷阱</li>
     *   <li><b>{@code process.waitFor(timeout, TimeUnit)}</b>：
     *       带超时的等待，避免 Python 进程因 MinerU API 故障而无限阻塞。
     *       超时后 {@code destroyForcibly()} 强制终止子进程</li>
     *   <li><b>输出大小限制</b>：
     *       逐行读取时累计字节数，超过 {@code maxOutputSizeMb} 上限立即终止进程并抛异常，
     *       防止大文件解析结果撑爆堆内存</li>
     *   <li><b>流式 JSON 解析（Jackson Streaming API）</b>：
     *       内层 JSON 使用 {@link JsonParser} 逐 token 扫描，找到 {@code md_content}
     *       字段即停止，不构建完整树模型。相比 {@code readTree()} 节省 3~5 倍内存</li>
     *   <li><b>预编译正则</b>：
     *       后处理正则提取为 {@code static final Pattern}，避免每次调用
     *       {@code String.replaceAll()} 时重复编译，提升性能并减少临时对象</li>
     * </ul>
     *
     * @param pythonPath Python 解释器路径
     * @param scriptPath 解析脚本路径
     * @param filePath   待解析文件路径
     * @return 解析后的 Markdown 纯文本
     * @throws Exception Python 脚本执行失败或 JSON 解析失败时抛出
     */
    private String executePythonScript(String pythonPath, String scriptPath, String filePath) throws Exception {
        // ===== 步骤1：构建命令行 =====
        List<String> command = new ArrayList<>();
        command.add(pythonPath);
        command.add(scriptPath);
        command.add(filePath);

        // ===== 步骤2：启动子进程 =====
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // ===== 步骤3：读取子进程输出（带大小限制） =====
        long maxBytes = (long) maxOutputSizeMb * 1024 * 1024;
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            long totalBytes = 0;
            while ((line = reader.readLine()) != null) {
                // 逐行追加，同时检查累计大小是否超过上限
                int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
                totalBytes += lineBytes;
                if (totalBytes > maxBytes) {
                    process.destroyForcibly();
                    throw new RuntimeException(
                            String.format("Python 脚本输出超过上限（%dMB），已终止进程。文件: %s",
                                    maxOutputSizeMb, filePath));
                }
                output.append(line);
            }
        }

        // ===== 步骤4：等待子进程退出（带超时） =====
        boolean finished = process.waitFor(PYTHON_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException(
                    "Python 脚本执行超时（" + PYTHON_TIMEOUT_MINUTES + " 分钟），已强制终止。文件: " + filePath);
        }
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("Python 脚本执行失败，退出码: " + exitCode + "，输出: " + output);
        }

        // ===== 步骤5：从输出中提取 JSON 片段 =====
        String outputStr = output.toString().trim();
        int jsonStart = outputStr.indexOf('{');
        int jsonEnd = outputStr.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            outputStr = outputStr.substring(jsonStart, jsonEnd + 1);
        }

        // ===== 步骤6：解析外层 JSON（外层很小，用树模型即可） =====
        JsonNode outerRoot = objectMapper.readTree(outputStr);

        if (outerRoot.get("success").asBoolean()) {
            String innerJsonString = outerRoot.path("content").asText();

            // ===== 步骤7：流式解析内层 JSON，避免全量加载树模型 =====
            // 内层 JSON 可能包含数百 MB 的 Markdown 内容，
            // 使用 JsonParser 逐 token 读取，只提取 md_content 字段，跳过其余节点
            String mdContent = null;
            JsonParser parser = objectMapper.getFactory().createParser(innerJsonString);
            JsonToken token;
            while ((token = parser.nextToken()) != null) {
                if (token == JsonToken.FIELD_NAME && "md_content".equals(parser.currentName())) {
                    parser.nextToken();
                    mdContent = parser.getValueAsString();
                    break;
                }
            }
            parser.close();

            if (mdContent == null) {
                // md_content 不存在，尝试以树模型回退解析（此时内容应较小）
                JsonNode innerRoot = objectMapper.readTree(innerJsonString);
                JsonNode resultsNode = innerRoot.path("results");
                return resultsNode.asText();
            }

            // ===== 步骤8：后处理清洗（使用预编译正则，避免每次调用重复编译） =====
            mdContent = HTML_TAG_PATTERN.matcher(mdContent).replaceAll(" ");
            mdContent = BRACKET_PATTERN.matcher(mdContent).replaceAll(" ");
            mdContent = HORIZONTAL_WHITESPACE_PATTERN.matcher(mdContent).replaceAll(" ").trim();
            mdContent = EXCESS_NEWLINES_PATTERN.matcher(mdContent).replaceAll("\n\n");

            return mdContent;
        } else {
            throw new RuntimeException("解析失败: " + outerRoot.get("error").asText());
        }
    }
}