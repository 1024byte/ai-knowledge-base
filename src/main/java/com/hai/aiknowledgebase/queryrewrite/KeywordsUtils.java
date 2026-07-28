package com.hai.aiknowledgebase.queryrewrite;

import com.hai.aiknowledgebase.service.ChineseTokenizerService;
import com.hai.aiknowledgebase.service.QueryRewriteConfigLoader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class KeywordsUtils {



    /** 词典配置加载器：提供同义词词典、固定映射、停用词，支持定时热加载 */
    private final QueryRewriteConfigLoader configLoader;

    /** 排除词助词分割正则：用于从排除词中剥离"的"、"了吗"等非关键词助词 */
    private static final Pattern EXCLUDE_PARTICLE_SPLIT = Pattern.compile("[的了吗呢吧啊呀嘛]+");


    /** 标点符号正则：用于清理查询文本中的中英文标点符号 */
    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("[，,、。.！!？?；;：:\"\u201C\u201D\"\"()（）]");
    /**
     * 排除词提取正则
     * <p>
     * 匹配模式："不包含/除了/不要/排除/不含" + 空格 + 排除内容。
     * 排除内容支持中英文、顿号/逗号/"和"/"&" 分隔的多个词。
     * <p>
     * 示例：<br>
     * "不包含糖的饮料" → 提取 "糖"<br>
     * "除了苹果、香蕉" → 提取 "苹果", "香蕉"<br>
     * "不要Java和Python" → 提取 "Java", "Python"
     */
    private static final Pattern EXCLUDE_PATTERN = Pattern.compile(
            "(?:不包含|除了|不要|排除|不含)[\\s]*([\\u4e00-\\u9fa5a-zA-Z0-9]+(?:[、，,和&\\s]+[\\u4e00-\\u9fa5a-zA-Z0-9]+)*)"
    );

    // ==================== 关键词提取 ====================

    /**
     * 简单关键词提取：基于标点符号分割
     *
     * <p>先将标点符号替换为空格，按空白字符分割，过滤单字和停用词，
     * 取前 5 个作为关键词。</p>
     *
     * <p>注意：此方法为简化版关键词提取，仅用于 L1 规则改写阶段。
     * L2 阶段使用 {@link ChineseTokenizerService#extractKeywords(String, int)} 获取更精确的结果。</p>
     *
     * @param text 待提取的文本
     * @return 最多 5 个关键词
     */
    public List<String> extractSimpleKeywords(String text) {
        String cleaned = PUNCTUATION_PATTERN.matcher(text).replaceAll(" ");
        String[] parts = cleaned.split("\\s+");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            p = p.trim();
            if (p.length() >= 2 && !configLoader.isStopWord(p)) {
                result.add(p);
            }
        }
        return result.stream().limit(5).toList();
    }

    /**
     * 提取排除关键词
     *
     * <h3>匹配模式</h3>
     * 使用 {@link #EXCLUDE_PATTERN} 正则匹配"不包含/除了/不要/排除/不含"后的内容。
     * 匹配到后按分隔符（顿号、逗号、"和"、"&"、空格）分割，再按助词
     * （"的"、"了吗"、"呢"、"吧"等）二次分割，只取助词前的首个有效词作为排除词。
     *
     * <h3>示例</h3>
     * <ul>
     *   <li>"不包含糖的饮料" → 提取 "糖"（"的"后的"饮料"是限定对象，不排除）</li>
     *   <li>"除了苹果、香蕉和橘子" → 提取 "苹果", "香蕉", "橘子"</li>
     *   <li>"不要Java和Python" → 提取 "Java", "Python"</li>
     *   <li>"排除咖啡的替代品" → 提取 "咖啡"（"替代品"是用户要找的，不排除）</li>
     * </ul>
     *
     * @param text 查询文本
     * @return 排除关键词列表
     */
    public List<String> extractExcludeKeywords(String text) {
        List<String> excludeKeywords = new ArrayList<>();
        Matcher matcher = EXCLUDE_PATTERN.matcher(text);
        while (matcher.find()) {
            String group = matcher.group(1);
            String[] parts = group.split("[、，,和&\\s]+");
            for (String part : parts) {
                // 按助词分割，只取第一个片段作为排除词（如 "糖的饮料" → "糖"）
                // "的"后面的内容通常是限定对象，不应作为排除词
                String[] subParts = EXCLUDE_PARTICLE_SPLIT.split(part.trim());
                if (subParts.length > 0) {
                    String trimmed = subParts[0].trim();
                    if (!trimmed.isEmpty() && trimmed.length() >= 2) {
                        excludeKeywords.add(trimmed);
                    }
                }
            }
        }
        return excludeKeywords;
    }



}
