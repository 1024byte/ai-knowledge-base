package com.hai.aiknowledgebase.common;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

@Slf4j
public class ContentAnalyzer {

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```[a-zA-Z]*\\s*[\\s\\S]*?```");
    private static final Pattern INLINE_CODE_PATTERN = Pattern.compile("`[^`]+`");
    private static final Pattern LEGAL_PATTERN = Pattern.compile("(第[零一二三四五六七八九十百千]+条|依据《|规定：|合同.*条款)");
    private static final Pattern TABLE_LINE_PATTERN = Pattern.compile("^\\s*\\|.*\\|\\s*$");

    /**
     * 分析文本内容，返回最匹配的内容分类
     */
    public ContentCategory analyze(String text) {
        if (text == null || text.isBlank()) {
            return ContentCategory.GENERAL;
        }

        int lines = text.split("\n").length;
        if (lines == 0) return ContentCategory.GENERAL;

        // 1. 检测技术类（代码块或行内代码密集）
        long codeBlockCount = CODE_BLOCK_PATTERN.matcher(text).results().count();
        long inlineCodeCount = INLINE_CODE_PATTERN.matcher(text).results().count();
        if (codeBlockCount > 2 || (codeBlockCount > 0 && inlineCodeCount > 5)) {
            log.debug("检测到技术文档特征: 代码块={}, 行内代码={}", codeBlockCount, inlineCodeCount);
            return ContentCategory.TECHNICAL;
        }

        // 2. 检测法律类（关键词命中）
        if (LEGAL_PATTERN.matcher(text).find()) {
            log.debug("检测到法律文档特征: 包含法条关键词");
            return ContentCategory.LEGAL;
        }

        // 3. 检测表格密集（超过 30% 的行是表格行）
        String[] allLines = text.split("\n");
        long tableLineCount = 0;
        for (String line : allLines) {
            if (TABLE_LINE_PATTERN.matcher(line).matches()) {
                tableLineCount++;
            }
        }
        double tableRatio = (double) tableLineCount / allLines.length;
        if (tableRatio > 0.3) {
            log.debug("检测到表格密集特征: 表格行占比={:.2f}", tableRatio);
            return ContentCategory.TABLE_HEAVY;
        }

        return ContentCategory.GENERAL;
    }
}
