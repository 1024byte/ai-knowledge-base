package com.hai.aiknowledgebase.common;

import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

@Slf4j
public class DocumentTypeClassifier {

    // 强特征（+3分）：几乎只有正式法律文件才有
    private static final Pattern STRONG_LEGAL = Pattern.compile(
            "(第[零一二三四五六七八九十百千]+条|第[0-9]+条|依据《[^》]+》|" + "本法|本条例|本规定|司法解释|最高人民法院|人民检察院|律师|诉讼|仲裁|判决)"
    );

    // 中特征（+2分）：法律文件常见，但商业合同/说明书也偶尔出现
    private static final Pattern MEDIUM_LEGAL = Pattern.compile(
            "甲方|乙方|签订|履行|违约|赔偿|生效日期|保密义务|知识产权归属"
    );

    // 弱特征（+1分）：极易误报，如你遇到的“合同条款”
    private static final Pattern WEAK_LEGAL = Pattern.compile(
            "合同.*条款|受.*约束|免责声明|法律允许.*范围|依据.*法律"
    );

    // 强负向特征（-5分）：只要出现，绝对不是法律文件（技术文档特征）
    private static final Pattern NEGATIVE_TECH = Pattern.compile(
            "API|SDK|调用|接口|参数|返回值|方法|类|配置|部署|实例|Bucket|Object|Region|Zone"
    );

    /**
     * 判断是否为法律文件
     * @param text 文档前 2000 个字符（只取前文判断，避免页脚干扰）
     * @return true 表示是法律文件
     */
    public static boolean isLegalDocument(String text) {
        // 只取前 2000 字符，避免被末尾的“免责声明”干扰
        String sample = text.length() > 2000 ? text.substring(0, 2000) : text;

        int score = 0;

        // 1. 强特征
        if (STRONG_LEGAL.matcher(sample).find()) {
            score += 3;
        }
        // 2. 中特征
        if (MEDIUM_LEGAL.matcher(sample).find()) {
            score += 2;
        }
        // 3. 弱特征（你的“合同条款”就在这里，只加 1 分，且容易被下面抵消）
        if (WEAK_LEGAL.matcher(sample).find()) {
            score += 1;
        }
        // 4. 技术文档负向特征（减 5 分，一票否决）
        if (NEGATIVE_TECH.matcher(sample).find()) {
            score -= 5;
        }

        // 5. 额外逻辑：如果正文包含大量“API/SDK”等词汇，即使有“合同”字样也强制非法律
        long techKeywordCount = Pattern.compile("\\b(API|SDK|HTTP|TCP|JSON|XML|Cloud|Instance)\\b")
                .matcher(sample).results().count();
        if (techKeywordCount >= 3) {
            score -= 3;
        }

        // 判断阈值：得分 >= 3 才认为是法律文件
        boolean isLegal = score >= 3;

        log.debug("法律文件判断: score={}, 结果={}, 预览={}", score, isLegal, sample.substring(0, Math.min(100, sample.length())));
        return isLegal;
    }
}