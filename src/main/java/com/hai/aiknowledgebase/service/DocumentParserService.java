package com.hai.aiknowledgebase.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hai.aiknowledgebase.common.CustomDocument;
import com.hai.aiknowledgebase.common.FileUtils;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.units.qual.C;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.hai.aiknowledgebase.common.FileUtils.getFileExtension;

@Slf4j
@Service
public class DocumentParserService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${mineru.python.path:E:/Anaconda_envs/envs/pytorch_gpu/python.exe}")
    private String pythonPath;

    @Value("${mineru.scripts.docx}")
    private String docxScriptPath;

    @Value("${mineru.scripts.pdf}")
    private String pdfScriptPath;

    // 纯文本扩展名（直接读取）
    private static final Set<String> PLAIN_TEXT_EXTS = Set.of("md", "txt");

    // 使用 python-docx 解析的扩展名
    private static final Set<String> DOCX_EXTS = Set.of("docx");

    // 使用 MinerU 解析的扩展名（复杂文档）
    private static final Set<String> MINERU_EXTS = Set.of("pdf", "pptx", "xlsx", "doc", "ppt", "xls", "jpg", "jpeg", "png", "bmp", "tiff");


    /**
     * 根据文件类型选择解析方式
     */
    public CustomDocument parseDocument(Path filePath, String fileName) throws Exception {
        String extension = getFileExtension(fileName).toLowerCase();
        if (PLAIN_TEXT_EXTS.contains(extension)) {
            // 纯文本：直接读取
            log.info("直接读取纯文本: {}", extension);
            String content = FileUtils.loadDocumentContent(filePath.toFile(), fileName);
           return CustomDocument.builder().fileName(fileName).content(content).format(CustomDocument.Format.fromString(extension)).build();
        } else if (DOCX_EXTS.contains(extension)) {
            String content = executePythonScript(pythonPath, docxScriptPath, filePath.toString());
            // DOCX：使用 python-docx
            log.info("使用 python-docx 解析: {}", extension);
            return CustomDocument.builder().fileName(fileName).content(content).format(CustomDocument.Format.fromString(extension)).build();
        } else if (MINERU_EXTS.contains(extension)) {
            // 其他复杂文档：使用 MinerU
            log.info("使用 MinerU 解析: {}", extension);
            String content = executePythonScript(pythonPath, pdfScriptPath, filePath.toString());
            return CustomDocument.builder().fileName(fileName).content(content).format(CustomDocument.Format.fromString(extension)).build();

        } else {
            // 未知类型：尝试用 MinerU 兜底
            log.warn("未知文件类型: {}, 尝试使用 MinerU", extension);
            String content = executePythonScript(pythonPath, pdfScriptPath, filePath.toString());
            return CustomDocument.builder().fileName(fileName).content(content).format(CustomDocument.Format.UNKNOWN).build();

        }
    }

    /**
     * 执行 Python 脚本并返回 JSON 中的 content
     */
    private String executePythonScript(String pythonPath, String scriptPath, String filePath) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(pythonPath);
        command.add(scriptPath);
        command.add(filePath);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Python 脚本执行失败，退出码: " + exitCode + "，输出: " + output);
        }
        String outputStr = output.toString().trim();
        // 如果输出包含非 JSON 前缀（如错误日志），尝试提取 JSON
        int jsonStart = outputStr.indexOf('{');
        int jsonEnd = outputStr.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            outputStr = outputStr.substring(jsonStart, jsonEnd + 1);
        }
        JsonNode jsonNode = objectMapper.readTree(outputStr);
        if (jsonNode.get("success").asBoolean()) {
            return jsonNode.get("content").asText();
        } else {
            throw new RuntimeException("解析失败: " + jsonNode.get("error").asText());
        }
    }
}