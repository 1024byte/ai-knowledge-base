package com.hai.aiknowledgebase.service;

import dev.langchain4j.model.TokenCountEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * MarkdownDocumentChunker 单元测试
 *
 * <p>覆盖：</p>
 * <ul>
 *   <li>同级别编号子标题规范化（H2 + H2 编号子标题 → H2 + H3 子）</li>
 *   <li>完整切片流程（parseSections → iterativeChunk → mergeSmallChunks → applyOverlap）</li>
 *   <li>contextPrefix 层级路径正确性</li>
 *   <li>parent_text 元数据正确性</li>
 *   <li>边界情况（单个编号标题不触发、父标题有内容不触发、不同级别标题不触发）</li>
 *   <li>真实复杂不规则文档切片演练</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MarkdownDocumentChunker 切片器")
class MarkdownDocumentChunkerTest {

    @Mock
    private TokenCountEstimator tokenEstimator;

    /**
     * 模拟 Token 估算：1 字符 = 1 token（简化模型，便于预测切分行为）。
     * 使用 lenient() 模式，因为 parseSections 相关测试不调用 tokenEstimator。
     */
    @BeforeEach
    void setUp() {
        lenient().when(tokenEstimator.estimateTokenCountInText(anyString()))
                .thenAnswer(invocation -> {
                    String text = invocation.getArgument(0);
                    return text == null ? 0 : text.length();
                });
    }

    /**
     * 创建切片器实例，embeddingModel=null 表示关闭语义切分，走纯 token 降级路径。
     */
    private MarkdownDocumentChunker createChunker(int minTokens, int maxTokens, double overlapRatio) {
        return new MarkdownDocumentChunker(
                minTokens,
                maxTokens,
                overlapRatio,
                tokenEstimator,
                null,
                0.7
        );
    }

    // ==================== 同级别编号子标题规范化测试 ====================

    @Nested
    @DisplayName("同级别编号子标题规范化（normalizeNumberedSiblingHeadings）")
    class NumberedSiblingNormalizationTest {

        @Test
        @DisplayName("4个连续编号子标题应被提升为父标题的子节点")
        void shouldPromoteNumberedSiblingsToChildren() {
            String markdown = """
                    ## 腾讯云智能数智人系统优化方案：优化方案4选3

                    ## 1. 数据收集与分析

                    （1）详尽收集用户反馈及实际使用数据，深入剖析数智人响应不精确、个性化服务缺失等问题的根本成因。

                    （2）利用先进的机器学习算法对用户互动数据进行深入挖掘，以准确识别用户的实际需求和个性化偏好。

                    ## 2. 模型优化与训练

                    （1）针对数智人响应不精确的问题，对现有自然语言处理模型进行优化升级，增强其对语义和意图的准确理解能力。

                    （2）引入个性化推荐算法，基于用户的历史交互记录和个性化偏好，为数智人提供更具针对性的个性化服务能力。

                    ## 3. 界面与交互设计优化

                    （1）重新设计数智人系统的界面布局，力求简洁直观，易于用户操作和理解。

                    （2）优化数智人的交互流程，减少多余步骤，提升整体交互的流畅性和便捷性。

                    ## 4. 测试与迭代

                    （1）对优化后的数智人系统进行全面严格的测试，确保各项功能稳定运行，用户体验得到显著提升。

                    （2）根据测试反馈及用户反馈，持续进行迭代优化，以不断提升数智人系统的性能和服务质量。
                    """;

            MarkdownDocumentChunker chunker = createChunker(1, 500, 0.0);
            List<MarkdownDocumentChunker.Section> roots = chunker.parseSections(markdown);

            assertThat(roots).hasSize(1);
            MarkdownDocumentChunker.Section parent = roots.get(0);
            assertThat(parent.level).isEqualTo(2);
            assertThat(parent.title).startsWith("## 腾讯云智能数智人系统优化方案");

            assertThat(parent.children).hasSize(4);
            assertThat(parent.children.get(0).level).isEqualTo(3);
            assertThat(parent.children.get(0).title).startsWith("### 1. 数据收集与分析");
            assertThat(parent.children.get(1).title).startsWith("### 2. 模型优化与训练");
            assertThat(parent.children.get(2).title).startsWith("### 3. 界面与交互设计优化");
            assertThat(parent.children.get(3).title).startsWith("### 4. 测试与迭代");
        }
    }

    // ==================== 完整切片流程测试 ====================

    @Nested
    @DisplayName("完整切片流程（chunk 方法）")
    class FullChunkPipelineTest {

        @Test
        @DisplayName("不规则标题文档切片后应携带完整层级路径")
        void shouldProduceChunksWithCorrectContextPrefix() {
            String markdown = """
                    ## 腾讯云智能数智人系统优化方案：优化方案4选3

                    ## 1. 数据收集与分析

                    （1）详尽收集用户反馈及实际使用数据，深入剖析数智人响应不精确、个性化服务缺失等问题的根本成因。

                    （2）利用先进的机器学习算法对用户互动数据进行深入挖掘，以准确识别用户的实际需求和个性化偏好。

                    ## 2. 模型优化与训练

                    （1）针对数智人响应不精确的问题，对现有自然语言处理模型进行优化升级，增强其对语义和意图的准确理解能力。

                    （2）引入个性化推荐算法，基于用户的历史交互记录和个性化偏好，为数智人提供更具针对性的个性化服务能力。

                    ## 3. 界面与交互设计优化

                    （1）重新设计数智人系统的界面布局，力求简洁直观，易于用户操作和理解。

                    （2）优化数智人的交互流程，减少多余步骤，提升整体交互的流畅性和便捷性。

                    ## 4. 测试与迭代

                    （1）对优化后的数智人系统进行全面严格的测试，确保各项功能稳定运行，用户体验得到显著提升。

                    （2）根据测试反馈及用户反馈，持续进行迭代优化，以不断提升数智人系统的性能和服务质量。
                    """;

            MarkdownDocumentChunker chunker = createChunker(1, 150, 0.0);
            List<MarkdownDocumentChunker.Chunk> chunks = chunker.chunk(markdown);

            assertThat(chunks).isNotEmpty();

            String parentTitle = "腾讯云智能数智人系统优化方案：优化方案4选3";

            boolean hasChild1 = false, hasChild2 = false, hasChild3 = false, hasChild4 = false;
            for (MarkdownDocumentChunker.Chunk chunk : chunks) {
                String prefix = chunk.contextPrefix();
                String text = chunk.text();

                if (prefix.contains("数据收集与分析")) {
                    hasChild1 = true;
                    assertThat(prefix).contains(parentTitle);
                    assertThat(text).contains("详尽收集用户反馈");
                }
                if (prefix.contains("模型优化与训练")) {
                    hasChild2 = true;
                    assertThat(prefix).contains(parentTitle);
                    assertThat(text).contains("自然语言处理模型进行优化升级");
                }
                if (prefix.contains("界面与交互设计优化")) {
                    hasChild3 = true;
                    assertThat(prefix).contains(parentTitle);
                    assertThat(text).contains("重新设计数智人系统的界面布局");
                }
                if (prefix.contains("测试与迭代")) {
                    hasChild4 = true;
                    assertThat(prefix).contains(parentTitle);
                    assertThat(text).contains("进行全面严格的测试");
                }
            }

            assertThat(hasChild1).as("应包含 '1. 数据收集与分析' 的 chunk").isTrue();
            assertThat(hasChild2).as("应包含 '2. 模型优化与训练' 的 chunk").isTrue();
            assertThat(hasChild3).as("应包含 '3. 界面与交互设计优化' 的 chunk").isTrue();
            assertThat(hasChild4).as("应包含 '4. 测试与迭代' 的 chunk").isTrue();
        }

        @Test
        @DisplayName("每个 chunk 应包含 parent_text 元数据")
        void shouldHaveParentTextMetadata() {
            String markdown = """
                    ## 腾讯云智能数智人系统优化方案：优化方案4选3

                    ## 1. 数据收集与分析

                    （1）详尽收集用户反馈及实际使用数据，深入剖析数智人响应不精确、个性化服务缺失等问题的根本成因。

                    （2）利用先进的机器学习算法对用户互动数据进行深入挖掘，以准确识别用户的实际需求和个性化偏好。

                    ## 2. 模型优化与训练

                    （1）针对数智人响应不精确的问题，对现有自然语言处理模型进行优化升级，增强其对语义和意图的准确理解能力。

                    （2）引入个性化推荐算法，基于用户的历史交互记录和个性化偏好，为数智人提供更具针对性的个性化服务能力。

                    ## 3. 界面与交互设计优化

                    （1）重新设计数智人系统的界面布局，力求简洁直观，易于用户操作和理解。

                    （2）优化数智人的交互流程，减少多余步骤，提升整体交互的流畅性和便捷性。

                    ## 4. 测试与迭代

                    （1）对优化后的数智人系统进行全面严格的测试，确保各项功能稳定运行，用户体验得到显著提升。

                    （2）根据测试反馈及用户反馈，持续进行迭代优化，以不断提升数智人系统的性能和服务质量。
                    """;

            MarkdownDocumentChunker chunker = createChunker(1, 150, 0.0);
            List<MarkdownDocumentChunker.Chunk> chunks = chunker.chunk(markdown);

            assertThat(chunks).isNotEmpty();

            for (MarkdownDocumentChunker.Chunk chunk : chunks) {
                String parentText = chunk.metadata().get("parent_text");
                assertThat(parentText)
                        .as("chunk '%s' 应有 parent_text 元数据", chunk.contextPrefix())
                        .isNotNull()
                        .isNotBlank();
            }
        }
    }

    // ==================== 边界情况测试 ====================

    @Nested
    @DisplayName("边界情况")
    class EdgeCaseTest {

        @Test
        @DisplayName("仅1个编号标题不应触发规范化（至少需要2个）")
        void shouldNotNormalizeSingleNumberedHeading() {
            String markdown = """
                    ## 项目概述

                    ## 1. 背景介绍

                    这是一段背景介绍文本，详细描述了项目的来龙去脉。
                    """;

            MarkdownDocumentChunker chunker = createChunker(1, 500, 0.0);
            List<MarkdownDocumentChunker.Section> roots = chunker.parseSections(markdown);

            assertThat(roots).hasSize(2);
            assertThat(roots.get(0).level).isEqualTo(2);
            assertThat(roots.get(0).children).isEmpty();
            assertThat(roots.get(1).level).isEqualTo(2);
            assertThat(roots.get(1).children).isEmpty();
        }

        @Test
        @DisplayName("父标题有内容时不应触发规范化")
        void shouldNotNormalizeWhenParentHasContent() {
            String markdown = """
                    ## 项目概述

                    这是父标题的详细内容，包含了项目背景、目标和范围等信息。

                    ## 1. 技术方案

                    技术方案的具体内容...

                    ## 2. 实施计划

                    实施计划的具体内容...
                    """;

            MarkdownDocumentChunker chunker = createChunker(1, 500, 0.0);
            List<MarkdownDocumentChunker.Section> roots = chunker.parseSections(markdown);

            assertThat(roots).hasSize(3);
            for (MarkdownDocumentChunker.Section root : roots) {
                assertThat(root.level).isEqualTo(2);
                assertThat(root.children).isEmpty();
            }
        }

        @Test
        @DisplayName("不同级别标题不受影响")
        void shouldNotAffectDifferentLevelHeadings() {
            String markdown = """
                    # 第一章

                    ## 1. 第一节

                    第一节内容...

                    ## 2. 第二节

                    第二节内容...
                    """;

            MarkdownDocumentChunker chunker = createChunker(1, 500, 0.0);
            List<MarkdownDocumentChunker.Section> roots = chunker.parseSections(markdown);

            assertThat(roots).hasSize(1);
            assertThat(roots.get(0).level).isEqualTo(1);
            assertThat(roots.get(0).children).hasSize(2);
            assertThat(roots.get(0).children.get(0).level).isEqualTo(2);
            assertThat(roots.get(0).children.get(1).level).isEqualTo(2);
        }

        @Test
        @DisplayName("中文数字编号子标题也应被识别")
        void shouldRecognizeChineseNumberedHeadings() {
            String markdown = """
                    ## 系统架构设计方案

                    ## 一、整体架构

                    整体架构的描述...

                    ## 二、模块划分

                    模块划分的详细说明...

                    ## 三、接口设计

                    接口设计的规范...
                    """;

            MarkdownDocumentChunker chunker = createChunker(1, 500, 0.0);
            List<MarkdownDocumentChunker.Section> roots = chunker.parseSections(markdown);

            assertThat(roots).hasSize(1);
            assertThat(roots.get(0).children).hasSize(3);
            assertThat(roots.get(0).children.get(0).level).isEqualTo(3);
            assertThat(roots.get(0).children.get(0).title).startsWith("### 一、整体架构");
        }

        @Test
        @DisplayName("括号编号子标题也应被识别")
        void shouldRecognizeParenthesizedNumberedHeadings() {
            String markdown = """
                    ## 实施步骤

                    ## （1）需求分析

                    需求分析阶段的工作内容...

                    ## （2）系统设计

                    系统设计阶段的工作内容...

                    ## （3）开发测试

                    开发测试阶段的工作内容...
                    """;

            MarkdownDocumentChunker chunker = createChunker(1, 500, 0.0);
            List<MarkdownDocumentChunker.Section> roots = chunker.parseSections(markdown);

            assertThat(roots).hasSize(1);
            assertThat(roots.get(0).children).hasSize(3);
            assertThat(roots.get(0).children.get(0).level).isEqualTo(3);
        }
    }

    // ==================== 真实复杂文档切片演练 ====================

    @Nested
    @DisplayName("真实复杂文档切片演练")
    class RealWorldDocumentTest {

        @Test
        @DisplayName("打印复杂不规则文档的切片结果")
        void printChunksForComplexDocument() {
            String markdown = """
                    ## 1. 2. 4-1

                    智能卖点生成系统业务模块中用户反映最强烈的几个问题及解释：反映问题4选3

                    ## 1. 卖点生成不准确

                    用户反馈实例：生成的卖点与产品实际功能或特性不符，如某款手机的"超长续航"卖点实际上并不符合其电池性能。

                    用户不满原因：卖点无法准确反映产品优势，导致营销信息误导消费者。

                    影响：降低用户对产品的信任度，影响销售转化率，可能引发负面口碑。

                    ## 2. 生成速度过慢

                    用户反馈实例：在市场急需快速推出新产品时，卖点生成系统需要数小时甚至数天才能生成满意的卖点。

                    用户不满原因：卖点生成过程耗时过长，无法满足快速变化的市场需求。

                    影响：延误产品上市时间，错失市场机会，可能被竞争对手抢占先机。

                    ## 3. 缺乏个性化定制

                    用户反馈实例：不同市场或客户群体对同一产品的关注点不同，但系统生成的卖点却完全相同。

                    用户不满原因：生成的卖点过于通用，无法针对不同市场和客户群体进行个性化调整。

                    影响：降低营销活动的针对性和有效性，可能无法吸引特定目标群体的注意。

                    ## 4. 用户界面不友好

                    用户反馈实例：系统操作复杂，需要多次点击和输入才能完成卖点生成，且用户界面布局混乱。

                    用户不满原因：系统操作不够直观易用，增加了用户的学习和使用成本。

                    影响：降低用户的使用体验，可能导致用户流失或减少对系统的依赖。

                    ## 1. 2. 4-2

                    ## 智能卖点生成系统优化方案：优化方案 4 选 3

                    ## 1. 引入更先进的机器学习算法

                    实施步骤:

                    调研最新的自然语言处理和机器学习算法。

                    选择适合卖点生成的算法进行测试和验证。

                    集成到现有系统中，进行性能优化和调试。

                    期望效果：显著提高卖点与产品特性的匹配度，加快生成速度，减少用户等待时间。

                    ## 2. 增加个性化定制功能

                    实施步骤:

                    设计个性化设置界面，允许用户输入目标市场和客户群体信息。

                    根据用户输入调整卖点生成策略，生成个性化的卖点。

                    进行用户测试，收集反馈，优化个性化定制功能。

                    期望效果：增强卖点的针对性和吸引力，提高营销活动的效果和转化率。

                    ## 3. 优化用户界面和交互设计

                    ## 实施步骤:

                    进行用户调研，收集关于用户界面和交互的反馈意见。

                    根据用户反馈重新设计用户界面和交互流程。

                    进行用户测试，验证新设计的易用性和用户满意度。

                    期望效果：降低用户学习成本，提升使用体验，增加用户对系统的满意度和忠诚度。

                    ## 4. 建立持续反馈和迭代机制

                    实施步骤:

                    设立用户反馈渠道，如在线调查、用户访谈等。

                    定期收集和分析用户反馈，识别系统问题和改进点。

                    制定迭代计划，不断优化系统功能和性能。

                    期望效果：确保系统始终满足用户需求，保持市场竞争力，不断提升用户体验和服务质量。

                    # 1.2.5 腾讯云智能数智人系统业务模块效果优化

                    # 请勿修改答题卷，在指定单元格内填写答案

                    ## 1. 2. 5-1

                    ## 腾讯云智能数智人系统业务模块中用户反映最强烈的几个问题及解释：

                    ## 1. 数智人响应不准确

                    用户在与数智人交互时，常常遇到数智人理解错误或回答不相关的问题，这导致用户需要多次重复或更正指令，增加了使用难度。

                    ## 2. 缺乏个性化交互能力

                    数智人在与用户交互时，无法根据用户的个人喜好、历史交互记录等信息提供个性化的服务和建议，使得用户体验显得单调且缺乏针对性。

                    ## 3. 交互流畅度不足

                    数智人在处理用户指令时，有时会出现延迟或卡顿现象，影响交互的实时性和流畅度，给用户带来界面设计不够友好：数智人系统的界面设计可能存在操作复杂、布局不合理等问题，导致用户在使用时感到困惑或不适。

                    ## 1. 2. 5-2

                    ## 腾讯云智能数智人系统优化方案：优化方案4选3

                    ## 1. 数据收集与分析

                    （1）详尽收集用户反馈及实际使用数据，深入剖析数智人响应不精确、个性化服务缺失等问题的根本成因。

                    （2）利用先进的机器学习算法对用户互动数据进行深入挖掘，以准确识别用户的实际需求和个性化偏好。

                    ## 2. 模型优化与训练

                    （1）针对数智人响应不精确的问题，对现有自然语言处理模型进行优化升级，增强其对语义和意图的准确理解能力。

                    （2）引入个性化推荐算法，基于用户的历史交互记录和个性化偏好，为数智人提供更具针对性的个性化服务能力。

                    ## 3. 界面与交互设计优化

                    （1）重新设计数智人系统的界面布局，力求简洁直观，易于用户操作和理解。

                    （2）优化数智人的交互流程，减少多余步骤，提升整体交互的流畅性和便捷性。

                    ## 4. 测试与迭代

                    （1）对优化后的数智人系统进行全面严格的测试，确保各项功能稳定运行，用户体验得到显著提升。

                    （2）根据测试反馈及用户反馈，持续进行迭代优化，以不断提升数智人系统的性能和服务质量。

                    ## 期望优化效果:

                    1. 数智人的响应准确性将得到显著提升，用户与数智人的交互将更为顺畅高效。

                    2. 数智人将具备强大的个性化服务能力，能够根据用户的个性化需求提供定制化的服务。

                    3. 数智人系统的界面设计将更加友好易用，用户在使用过程中将感受到更高的便捷性和舒适性。

                    4. 整体用户体验和服务质量将得到显著提升，用户的满意度和使用意愿将显著增加。
                    """;

            // 使用线上 GENERAL 默认参数：minTokens=100, maxTokens=512, overlapRatio=0.1
            MarkdownDocumentChunker chunker = createChunker(100, 512, 0.1);
            List<MarkdownDocumentChunker.Chunk> chunks = chunker.chunk(markdown);

            // 同时输出到控制台和文件（避免终端编码问题）
            StringWriter sw = new StringWriter();
            PrintWriter out = new PrintWriter(sw);

            out.println("========================================");
            out.println("  复杂不规则文档切片结果");
            out.println("  总 chunk 数: " + chunks.size());
            out.println("========================================");

            for (int i = 0; i < chunks.size(); i++) {
                MarkdownDocumentChunker.Chunk c = chunks.get(i);
                out.println();
                out.println("--- Chunk[" + i + "] ---");
                out.println("  tokens      : " + c.tokenCount());
                out.println("  contextPrefix: " + c.contextPrefix());
                out.println("  text length : " + c.text().length() + " chars");
                out.println("  text preview (first 200 chars):");
                String preview = c.text().length() > 200
                        ? c.text().substring(0, 200) + "..."
                        : c.text();
                out.println("    " + preview.replace("\n", "\n    "));
                out.println("  metadata    : " + c.metadata());
            }

            out.println();
            out.println("========================================");
            out.println("  切片完成，共 " + chunks.size() + " 个 chunk");
            out.println("========================================");
            out.flush();

            String result = sw.toString();
            System.out.println(result);

            // 写入文件
            try (FileWriter fw = new FileWriter("target/chunk_test_output.txt", StandardCharsets.UTF_8)) {
                fw.write(result);
            } catch (IOException e) {
                System.err.println("写入文件失败: " + e.getMessage());
            }

            assertThat(chunks).isNotEmpty();
        }
    }
}