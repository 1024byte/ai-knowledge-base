package com.hai.aiknowledgebase.common;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

        // 只取前 3000 字符作为样本（避免末尾页脚污染，同时提升性能）
        String sample = text.length() > 3000 ? text.substring(0, 3000) : text;
        String[] lines = sample.split("\n");
        int totalLines = lines.length;
        if (totalLines == 0) return ContentCategory.GENERAL;

        // ==================== 1. 计算各维度得分 ====================
        double techScore = 0;
        double legalScore = 0;
        double tableScore = 0;

        // --- 技术得分 ---
        long codeBlockCount = CODE_BLOCK_PATTERN.matcher(sample).results().count();
        long inlineCodeCount = INLINE_CODE_PATTERN.matcher(sample).results().count();
        // 强特征：代码块（+3分/个），行内代码（+1分/个），但限制上限避免失真
        techScore = Math.min(10, codeBlockCount * 3 + inlineCodeCount * 0.5);
        // 额外惩罚：如果只有1个代码块但无行内代码，可能是日志路径，不算强技术，降权
        if (codeBlockCount == 1 && inlineCodeCount < 2) {
            techScore = Math.min(2, techScore);
        }

        // --- 法律得分 ---
        // 强特征（+3分）：严谨的法律条文
        long strongLegal = Pattern.compile("(第[零一二三四五六七八九十百千0-9]+条|依据《|本法|本条例|司法解释)").matcher(sample).results().count();
        // 弱特征（+1分）：商业合同用词
        long weakLegal = Pattern.compile("(甲方|乙方|签订|违约|赔偿|保密|知识产权|合同.*条款|受.*约束)").matcher(sample).results().count();
        legalScore = strongLegal * 3 + weakLegal * 1;
        // 技术文档负向抵消（如果出现大量API/SDK，法律分数打折）
        long techKeywords = Pattern.compile("\\b(API|SDK|HTTP|JSON|XML|部署|实例|Bucket|Region)\\b").matcher(sample).results().count();
        if (techKeywords > 5) {
            legalScore = legalScore * 0.5;
        }

        // --- 表格得分 ---
        long tableLineCount = 0;
        for (String line : lines) {
            if (TABLE_LINE_PATTERN.matcher(line).matches()) {
                tableLineCount++;
            }
        }
        double tableRatio = (double) tableLineCount / totalLines;
        // 超过 20% 行是表格，就按比例给分（最高 10 分）
        if (tableRatio > 0.2) {
            tableScore = Math.min(10, tableRatio * 15); // 30% -> 4.5分, 50% -> 7.5分
        }
        // 额外奖励：如果表格行数 > 10，强表格特征
        if (tableLineCount > 10) {
            tableScore += 2;
        }

        // ==================== 2. 决策逻辑（带降级保护） ====================
        log.debug("文档类型打分：技术={:.2f}, 法律={:.2f}, 表格={:.2f}", techScore, legalScore, tableScore);

        // 场景A：得分都很低（都 < 2），直接返回 GENERAL，避免强行分类
        if (techScore < 2 && legalScore < 2 && tableScore < 2) {
            return ContentCategory.GENERAL;
        }

        // 场景B：找出最高分
        double maxScore = Math.max(techScore, Math.max(legalScore, tableScore));

        // 场景C：如果最高分和第二高分差距小于 1.5，说明特征混合严重，为保证切片安全，回退 GENERAL
        List<Double> sorted = Arrays.asList(techScore, legalScore, tableScore);
        Collections.sort(sorted, Collections.reverseOrder());
        if (sorted.get(0) - sorted.get(1) < 1.5 && sorted.get(0) < 6) {
            log.debug("文档类型混合严重，最高分差值不足，回退为 GENERAL");
            return ContentCategory.GENERAL;
        }

        // 场景D：返回最高分对应的类型
        if (techScore == maxScore) {
            log.debug("判定为 TECHNICAL（技术文档）");
            return ContentCategory.TECHNICAL;
        } else if (legalScore == maxScore) {
            log.debug("判定为 LEGAL（法律文档）");
            return ContentCategory.LEGAL;
        } else {
            log.debug("判定为 TABLE_HEAVY（表格密集）");
            return ContentCategory.TABLE_HEAVY;
        }
    }
}
