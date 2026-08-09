package com.hai.aiknowledgebase.service;

import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * <h2>父上下文扩展器</h2>
 *
 * <p>在检索阶段，将每个命中 chunk 的文本替换为入库时预计算的 parent_text，
 * 然后按文本 hash 去重，实现零延迟的上下文扩展。</p>
 *
 * <h3>工作原理</h3>
 * <ol>
 *   <li><b>文本替换</b>：将每个命中 chunk 的 text 替换为自身的 parent_text</li>
 *   <li><b>去重</b>：相邻 chunk 的 parent_text 高度重叠，按段落 hash 去重，
 *       保留分数最高的版本</li>
 * </ol>
 *
 * <h3>为什么不合并？</h3>
 * <p>多主题文档（如试卷）中不同 chunk 的 parent_text 属于不同主题。
 * 合并会导致"卖点系统"和"数智人系统"的上下文混在一起，全部变成噪音。
 * 独立扩展保留每个 chunk 的主题边界，Rerank 精排后自然过滤掉无关主题。</p>
 *
 * @see com.hai.aiknowledgebase.config.ChunkingConfig#PARENT_WINDOW
 */
@Slf4j
@Component
public class ParentContextExpander {

    /**
     * <h3>执行父上下文扩展</h3>
     *
     * <p>两阶段处理：</p>
     * <ol>
     *   <li><b>文本替换</b>：将每个 chunk 的 text 替换为 parent_text</li>
     *   <li><b>去重</b>：相邻 chunk 的 parent_text 高度重叠，按文本 hash 去重</li>
     * </ol>
     *
     * @param results 检索结果列表（按分数降序）
     * @return 扩展并去重后的结果列表，分数不变
     */
    public List<HybridSearchService.RankedResult> expand(
            List<HybridSearchService.RankedResult> results) {
        if (results == null || results.isEmpty()) {
            return results;
        }

        // ===== 阶段1：独立扩展 =====
        // 每个 chunk 用自己的 parent_text 替换原始文本，不跨 chunk 合并
        int expandedCount = 0;
        List<HybridSearchService.RankedResult> expanded = new ArrayList<>(results.size());

        for (HybridSearchService.RankedResult r : results) {
            String parentText = r.getSegment().metadata().getString("parent_text");
            if (parentText != null && !parentText.isBlank()) {
                TextSegment expandedSegment = TextSegment.from(parentText, r.getSegment().metadata());
                expanded.add(new HybridSearchService.RankedResult(expandedSegment, r.getScore()));
                expandedCount++;
            } else {
                expanded.add(r);
            }
        }

        if (expandedCount > 0) {
            log.info("父上下文扩展: {} 个片段替换为 parent_text", expandedCount);
        }

        // ===== 阶段2：去重 =====
        // 相邻 chunk 的 parent_text 高度重叠（如 chunk 1 和 chunk 2 共享 4/5 内容）
        // 按段落 hash 去重，保留分数最高的
        int beforeDedup = expanded.size();
        List<HybridSearchService.RankedResult> deduplicated = deduplicateByParagraphHash(expanded);
        if (deduplicated.size() < beforeDedup) {
            log.info("父上下文去重: {} -> {} 个片段", beforeDedup, deduplicated.size());
        }

        return deduplicated;
    }

    /**
     * <h3>按段落级 hash 去重</h3>
     *
     * <p>与纯文本 hash 不同，本方法按段落拆分后计算 hash，
     * 能更精确地识别高度重叠的 parent_text（即使插入或删除了少量段落）。</p>
     *
     * <p>相邻 chunk 的 parent_text 示例：</p>
     * <pre>
     *   Chunk 1 parent_text: para_A + para_B + para_C + para_D + para_E
     *   Chunk 2 parent_text: para_B + para_C + para_D + para_E + para_F
     *   段落 hash: 去重后保留 para_A~F 的完整内容（分数高的胜出）
     * </pre>
     *
     * @param results 扩展后的结果列表
     * @return 去重后的结果列表，保持原始顺序
     */
    private List<HybridSearchService.RankedResult> deduplicateByParagraphHash(
            List<HybridSearchService.RankedResult> results) {
        if (results.size() <= 1) return results;

        Map<Integer, HybridSearchService.RankedResult> seen = new LinkedHashMap<>();
        for (HybridSearchService.RankedResult r : results) {
            int hash = computeParagraphHash(r.getSegment().text());
            HybridSearchService.RankedResult existing = seen.get(hash);
            if (existing == null || r.getScore() > existing.getScore()) {
                seen.put(hash, r);
            }
        }
        return new ArrayList<>(seen.values());
    }

    /**
     * <h3>计算段落级 hash</h3>
     *
     * <p>将文本按段落拆分，取所有段落的 hash 组合。
     * 两个 parent_text 的段落集合高度重叠时，hash 相同，视为重复。</p>
     *
     * <p>使用段落集合而非全文的原因：Chunk 1 的 parent_text 是 A+B+C+D+E，
     * Chunk 2 是 B+C+D+E+F，全文 hash 不同但 80% 重叠。
     * 段落级 hash 能识别这种重叠模式。</p>
     */
    private int computeParagraphHash(String text) {
        if (text == null || text.isBlank()) return 0;
        // 按空行拆分段落，每段 strip 后取 hash，组合成最终 hash
        String[] paragraphs = text.split("\n\n");
        int result = 1;
        for (String p : paragraphs) {
            String stripped = p.strip();
            if (!stripped.isEmpty()) {
                result = 31 * result + stripped.hashCode();
            }
        }
        return result;
    }
}