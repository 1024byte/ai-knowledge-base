package com.hai.aiknowledgebase.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DocumentParserService {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${mineru.script.path:src/main/resources/scripts/parse_pdf_cli.py}")
    private String scriptPath;

    @Value("${mineru.python.path:E:/Anaconda_envs/envs/pytorch_gpu/python.exe}")
    private String pythonPath;

    public String parsePdf(Path pdfPath) throws Exception {
        if (!pdfPath.toFile().exists()) {
            throw new RuntimeException("PDF 文件不存在: " + pdfPath);
        }

        log.info("开始解析 PDF: {}", pdfPath);

        List<String> command = new ArrayList<>();
        command.add(pythonPath);
        command.add(scriptPath);
        command.add(pdfPath.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            log.error("Python 脚本执行失败，退出码: {}", exitCode);
            throw new RuntimeException("Python 脚本执行失败，退出码: " + exitCode);
        }

        String outputStr = output.toString();
        log.debug("Python 脚本输出: {}", outputStr);

        JsonNode jsonNode = objectMapper.readTree(outputStr);
        if (jsonNode.get("success").asBoolean()) {
            return jsonNode.get("content").asText();
        } else {
            String error = jsonNode.get("error").asText();
            throw new RuntimeException("MinerU 解析失败: " + error);
        }
    }
}