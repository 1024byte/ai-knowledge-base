package com.hai.aiknowledgebase.service;

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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            log.info("使用 MinerU 解析: {}", extension);
            String content = executePythonScript(pythonPath, pdfScriptPath, filePath.toString());
            return CustomDocument.builder()
                    .fileName(fileName)
                    .content(content)
                    .format(CustomDocument.Format.fromString(extension))
                    .build();

        } else {
            // ===== 分支4：未知格式兜底 =====
            // 对于不在路由表中的扩展名，打出 WARN 日志后尝试用 MinerU 解析
            // 这是一种"死马当活马医"的策略，MinerU 对未知格式也有一定的容错能力
            log.warn("未知文件类型: {}, 尝试使用 MinerU", extension);
            String content = executePythonScript(pythonPath, pdfScriptPath, filePath.toString());
            return CustomDocument.builder()
                    .fileName(fileName)
                    .content(content)
                    .format(CustomDocument.Format.UNKNOWN)
                    .build();
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
     *   <li><b>{@code process.waitFor()}</b>：
     *       阻塞等待子进程退出。注意：必须<b>先读完输出再 waitFor</b>，
     *       否则输出缓冲区满也会导致死锁。当前代码先读后等，顺序正确</li>
     *   <li><b>JSON 片段提取</b>：
     *       Python 可能输出日志前缀（如 "Loading model..."），
     *       通过 {@code indexOf('{')} 和 {@code lastIndexOf('}')} 定位纯 JSON 部分</li>
     *   <li><b>两层 JSON 解析</b>：
     *       外层 {@code outerRoot} 包含 success 状态和 content 字符串，
     *       内层 {@code innerRoot} 的 content 字符串需要二次解析才能得到 results</li>
     *   <li><b>{@code fields().next()}</b>：
     *       results 是一个单 key 的 JSON 对象，key 是动态文件名，
     *       通过迭代器取第一个（也是唯一一个）entry 获取内容</li>
     *   <li><b>{@code replaceAll("[^\\S\\n]+", " ")}</b>：
     *       正则 {@code [^\S\n]} 匹配<b>非换行的空白字符</b>（空格、制表符等），
     *       将其合并为单个空格，但<b>保留换行符</b>不破坏段落结构</li>
     *   <li><b>{@code replaceAll("\\n{3,}", "\\n\\n")}</b>：
     *       将 3 个及以上的连续换行合并为 2 个（即一个空行），保持文档结构整洁</li>
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
        // 关键：合并 stderr 到 stdout，避免缓冲区满导致死锁
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // ===== 步骤3：读取子进程输出 =====
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        // ===== 步骤4：等待子进程退出并检查结果 =====
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Python 脚本执行失败，退出码: " + exitCode + "，输出: " + output);
        }

        // ===== 步骤5：从输出中提取 JSON 片段 =====
        // Python 脚本可能在输出 JSON 之前打印了日志（如 "Loading model..."），
        // 需要跳过这些非 JSON 前缀，只取 {} 之间的内容
        String outputStr = output.toString().trim();
        int jsonStart = outputStr.indexOf('{');
        int jsonEnd = outputStr.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            outputStr = outputStr.substring(jsonStart, jsonEnd + 1);
        }

        // ===== 步骤6：解析嵌套 JSON 结构 =====
        // 第一层：外层 JSON，包含 success 状态和 content 字符串
        JsonNode outerRoot = objectMapper.readTree(outputStr);

        if (outerRoot.get("success").asBoolean()) {
            // content 字段本身是一个 JSON 字符串，需要二次解析
            String innerJsonString = outerRoot.path("content").asText();
            JsonNode innerRoot = objectMapper.readTree(innerJsonString);

            // results 是一个单 key 对象，key 是动态文件名
            JsonNode resultsNode = innerRoot.path("results");

            if (resultsNode.isObject() && resultsNode.size() > 0) {
                // 取 results 中第一个（也是唯一一个）entry
                Map.Entry<String, JsonNode> entry = resultsNode.fields().next();
                // 提取 md_content 字段 —— 最终需要的 Markdown 文本
                String mdContent = entry.getValue().path("md_content").asText();

                // ===== 步骤7：后处理清洗 =====
                // 去除 HTML 标签（如 <div>, <span> 等）
                mdContent = mdContent.replaceAll("<[^>]+>", " ");
                // 去除中文括号注释（如 【注1】、【说明】 等）
                mdContent = mdContent.replaceAll("【[^】]*】", " ");
                // 合并水平空白字符（空格、制表符），但保留换行符不破坏段落结构
                mdContent = mdContent.replaceAll("[^\\S\\n]+", " ").trim();
                // 合并多余空行：3个及以上连续换行 → 2个换行（一个空行）
                mdContent = mdContent.replaceAll("\\n{3,}", "\n\n");

                return mdContent;
            }
            // results 为空或非对象时，直接返回其文本表示
            return resultsNode.asText();
        } else {
            throw new RuntimeException("解析失败: " + outerRoot.get("error").asText());
        }
    }
}