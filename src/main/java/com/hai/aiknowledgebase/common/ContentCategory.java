package com.hai.aiknowledgebase.common;

import lombok.Getter;

@Getter
public enum ContentCategory {

    TECHNICAL("technical", "技术文档"),
    LEGAL("legal", "法律合同文档"),
    TABLE_HEAVY("table_heavy", "表格密集文档"),
    GENERAL("general", "通用文档");

    // 库存储标识 + 中文描述
    private final String code;
    private final String desc;

    ContentCategory(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

}
