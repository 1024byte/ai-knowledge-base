package com.hai.aiknowledgebase.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <h2>试卷/阅读理解材料内容预处理器</h2>
 *
 * <p>将混合了正文、题目、词汇表、答案的试卷文档拆分为独立的"内容区域"，
 * 每个区域附加类型标记，后续分块时保留类型信息到 metadata 中。</p>
 *
 * <h3>处理流程</h3>
 * <pre>
 * 原始文档（混合内容）
 *     │
 *     ├── 阶段1：按 Markdown 标题（H1-H6）粗分 Section
 *     │      ├── Section "Passage 1" → 正文区
 *     │      ├── Section "题目"     → 题目区
 *     │      └── Section "重点词汇" → 词汇表
 *     │
 *     ├── 阶段2：对每个 Section 精细分类
 *     │      ├── 检测题目编号+选项模式 → QUESTION
 *     │      ├── 检测英文=中文模式     → VOCAB
 *     │      ├── 检测答案区标记        → ANSWER
 *     │      └── 以上都不匹配         → PASSAGE
 *     │
 *     └── 阶段3：合并相邻同类型区域，输出 ContentRegion 列表
 * </pre>
 *
 * <h3>设计理由</h3>
 * <p>分块器（MarkdownDocumentChunker）本身只负责"怎么切"，不关心"切的是什么"。
 * 本预处理器在分块前完成"内容分类"，让每个 chunk 知道自己的身份（正文/题目/词汇），
 * 检索时即可按类型过滤或降权。</p>
 *
 * @see MarkdownDocumentChunker 文档切片器
 * @see DocumentRouter 文档路由器
 */
@Slf4j
@Component
public class ExamPaperPreprocessor {

    /** 题目编号模式：如 "1. "、"2、 "、"3．" */
    private static final Pattern QUESTION_NUMBER = Pattern.compile(
            "(?m)^\\s*\\d{1,2}[.、．]\\s*\\S");

    /** 选项模式：如 "A. "、"B. "、"C. "、"D. " */
    private static final Pattern OPTION_LINE = Pattern.compile(
            "(?m)^\\s*[A-D][.、．]\\s*\\S");

    /** 词汇表模式：英文单词 = 中文释义 或 英文单词 中文释义 */
    private static final Pattern VOCAB_LINE = Pattern.compile(
            "^\\s*[a-zA-Z]{2,}(?:\\s*=\\s*|\\s{2,}|\\s+)[\\u4e00-\\u9fff\\u3000-\\u303f\\uff00-\\uffef]");

    /** 答案区标记 */
    private static final Pattern ANSWER_MARKER = Pattern.compile(
            "(?i)(答案[：:]|参考答案|正确答案|解析[：:]|Answer[s]?[:：]|Key[:：])");

    /** Markdown 标题 */
    private static final Pattern HEADING = Pattern.compile("(?m)^#{1,6}\\s+.+");

    /**
     * <h3>内容区域</h3>
     * <p>表示文档中一段连续、内容类型一致的区域。</p>
     *
     * @param type 内容类型
     * @param text 纯文本内容
     */
    public record ContentRegion(ContentType type, String text) {
        @Override
        public String toString() {
            return "ContentRegion{type=" + type + ", length=" + text.length() + "}";
        }
    }

    /**
     * <h3>内容类型枚举</h3>
     */
    public enum ContentType {
        PASSAGE("正文/阅读段落"),
        QUESTION("选择题/题目"),
        VOCAB("词汇表/单词释义"),
        ANSWER("答案/解析"),
        MIXED("混合内容");

        private final String description;

        ContentType(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * <h3>预处理入口：将混合文档拆分为分类内容区域</h3>
     *
     * <h4>核心逻辑</h4>
     * <ol>
     *   <li>按 Markdown 标题（H1-H6）粗分段落</li>
     *   <li>对每个段落精细分类</li>
     *   <li>合并相邻同类型段落</li>
     * </ol>
     *
     * @param content 原始文档文本
     * @return 分类后的内容区域列表
     */
    public List<ContentRegion> preprocess(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        List<ContentRegion> regions = new ArrayList<>();

        // 阶段1：按标题粗分段落
        List<Section> sections = splitByHeadings(content);

        if (sections.isEmpty()) {
            // 无标题结构，直接对全文分类
            ContentType type = classifyContent(content);
            if (type != ContentType.MIXED) {
                regions.add(new ContentRegion(type, content));
                return regions;
            }
            // 混合内容尝试按行精细切分
            return splitByLine(content);
        }

        // 阶段2：对每个 Section 分类
        for (Section section : sections) {
            ContentType type = classifyContent(section.text);
            regions.add(new ContentRegion(type, section.text));
        }

        // 阶段3：合并相邻同类型区域
        return mergeAdjacent(regions);
    }

    // ======================== 内部方法 ========================

    /**
     * 按 Markdown 标题（H1-H6）将文本拆分为 Section 列表。
     */
    private List<Section> splitByHeadings(String content) {
        List<Section> sections = new ArrayList<>();
        Matcher m = HEADING.matcher(content);

        int lastEnd = 0;
        String lastHeading = "";

        while (m.find()) {
            if (lastEnd > 0 || !lastHeading.isEmpty()) {
                String sectionText = content.substring(lastEnd, m.start()).trim();
                if (!sectionText.isEmpty()) {
                    sections.add(new Section(lastHeading, sectionText));
                }
            }
            lastHeading = m.group().trim();
            lastEnd = m.end();
        }

        // 最后一个 Section
        String lastText = content.substring(lastEnd).trim();
        if (!lastText.isEmpty()) {
            sections.add(new Section(lastHeading, lastText));
        }

        return sections;
    }

    /**
     * 对一段文本进行内容类型分类。
     * 按特征信号强度判定：题目 > 词汇表 > 答案 > 正文。
     */
    private ContentType classifyContent(String text) {
        if (text == null || text.isBlank()) {
            return ContentType.PASSAGE;
        }

        String[] lines = text.split("\n");
        int questionLines = 0;
        int optionLines = 0;
        int vocabLines = 0;
        boolean hasAnswerMarker = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (QUESTION_NUMBER.matcher(trimmed).find()) {
                questionLines++;
            } else if (OPTION_LINE.matcher(trimmed).find()) {
                optionLines++;
            }

            if (VOCAB_LINE.matcher(trimmed).find()) {
                vocabLines++;
            }

            if (ANSWER_MARKER.matcher(trimmed).find()) {
                hasAnswerMarker = true;
            }
        }

        int totalLines = lines.length;
        if (totalLines == 0) return ContentType.PASSAGE;

        // 题目特征：题目编号 + 选项成对出现
        if (questionLines >= 2 && optionLines >= 3) {
            return ContentType.QUESTION;
        }

        // 词汇表特征：英文=中文 行占比 > 50%
        if (vocabLines > 0 && (double) vocabLines / totalLines > 0.5) {
            return ContentType.VOCAB;
        }

        // 答案区特征
        if (hasAnswerMarker && totalLines < 20) {
            return ContentType.ANSWER;
        }

        // 混合特征：同时有题目编号和词汇表行
        if (questionLines > 0 && vocabLines > 0) {
            return ContentType.MIXED;
        }

        // 默认：正文
        return ContentType.PASSAGE;
    }

    /**
     * 无标题结构的混合内容，按行精细切分。
     */
    private List<ContentRegion> splitByLine(String content) {
        List<ContentRegion> regions = new ArrayList<>();
        String[] lines = content.split("\n");

        StringBuilder currentBuffer = new StringBuilder();
        ContentType currentType = null;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (currentBuffer.length() > 0) {
                    currentBuffer.append("\n");
                }
                continue;
            }

            ContentType lineType = classifyLine(trimmed);

            if (currentType == null) {
                currentType = lineType;
                currentBuffer = new StringBuilder(trimmed);
            } else if (lineType == currentType) {
                currentBuffer.append("\n").append(trimmed);
            } else {
                // 类型切换，保存当前 buffer
                if (currentBuffer.length() > 0) {
                    regions.add(new ContentRegion(currentType, currentBuffer.toString()));
                }
                currentType = lineType;
                currentBuffer = new StringBuilder(trimmed);
            }
        }

        // 最后一个 buffer
        if (currentBuffer.length() > 0 && currentType != null) {
            regions.add(new ContentRegion(currentType, currentBuffer.toString()));
        }

        return regions;
    }

    /**
     * 对单行文本进行内容类型分类。
     */
    private ContentType classifyLine(String line) {
        if (QUESTION_NUMBER.matcher(line).find() || OPTION_LINE.matcher(line).find()) {
            return ContentType.QUESTION;
        }
        if (VOCAB_LINE.matcher(line).find()) {
            return ContentType.VOCAB;
        }
        if (ANSWER_MARKER.matcher(line).find()) {
            return ContentType.ANSWER;
        }
        return ContentType.PASSAGE;
    }

    /**
     * 合并相邻同类型区域。
     */
    private List<ContentRegion> mergeAdjacent(List<ContentRegion> regions) {
        if (regions.size() <= 1) return regions;

        List<ContentRegion> merged = new ArrayList<>();
        ContentRegion current = regions.get(0);

        for (int i = 1; i < regions.size(); i++) {
            ContentRegion next = regions.get(i);
            if (current.type() == next.type()) {
                current = new ContentRegion(current.type(),
                        current.text() + "\n\n" + next.text());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    /**
     * 内部类：文档 Section
     */
    private record Section(String heading, String text) {
    }
}