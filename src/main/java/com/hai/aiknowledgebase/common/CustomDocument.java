package com.hai.aiknowledgebase.common;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CustomDocument {
    private String fileName;
    private Format format;          // 原始文件格式
    private String content;         // 提取后的纯文本/Markdown内容
    private String metadata;        // 可选的元数据（作者、日期等）

    public enum Format {
        PDF, DOCX, MD, TXT, HTML, EXCEL, CSV, IMAGE_PNG, IMAGE_JPG, UNKNOWN;

        public static Format fromString(String value) {
            if (value == null) {
                return UNKNOWN;
            }
            for (Format format : Format.values()) {
                if (format.name().equalsIgnoreCase(value)) {
                    return format;
                }
            }
            return UNKNOWN;
        }
    }
}
