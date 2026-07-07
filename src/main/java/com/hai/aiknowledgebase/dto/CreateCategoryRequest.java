package com.hai.aiknowledgebase.dto;

import lombok.Data;

@Data
public class CreateCategoryRequest {
    private String name;        // 分类名称（如：财务报告）
    private String description; // 分类描述（可选）
}