package com.hai.aiknowledgebase.common;

import com.hai.aiknowledgebase.exception.BusinessException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

public class FileUtils {

    public static String getFileExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < filename.length() - 1) {
            return filename.substring(dotIndex + 1).toLowerCase();
        }
        return "";
    }

    public static String loadDocumentContent(File file, String filename) throws IOException {
        String extension = filename.substring(filename.lastIndexOf(".")).toLowerCase();

        switch (extension) {
            case ".txt":
            case ".md":
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);
            case ".pdf":
//                log.warn("PDF处理暂未完全实现，建议先转换为txt格式");
                return Files.readString(file.toPath(), StandardCharsets.UTF_8);
            default:
                throw new BusinessException(ResultCode.UNSUPPORTED_FILE_TYPE, extension);
        }
    }

    /**
     * 生成唯一文件名
     */
    public static String generateUniqueFilename(String originalFilename) {
        String uuid = UUID.randomUUID().toString().substring(0, 8);
        return uuid + "_" + originalFilename;
    }

    /**
     * 清理文件名
     */
    public static String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "unknown.txt";
        }

        String safeName = filename.replaceAll("\\.\\.", "_")
                .replaceAll("/", "_")
                .replaceAll("\\\\", "_");

        int lastSeparator = Math.max(safeName.lastIndexOf('/'), safeName.lastIndexOf('\\'));
        if (lastSeparator >= 0) {
            safeName = safeName.substring(lastSeparator + 1);
        }

        if (safeName.isEmpty()) {
            safeName = "document_" + System.currentTimeMillis() + ".txt";
        }

        return safeName;
    }
}
