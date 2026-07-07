package com.hai.aiknowledgebase.dto;

import lombok.Data;

@Data
public class FileCategoryDTO {
    private String category;    // 分类名称
    private String description; // 分类描述（可选）
}