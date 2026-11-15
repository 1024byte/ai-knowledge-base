package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.common.Role;
import com.hai.aiknowledgebase.dto.KnowledgeDocumentChunkMergeDTO;
import com.hai.aiknowledgebase.dto.KnowledgeDocumentChunkSplitDTO;
import com.hai.aiknowledgebase.dto.KnowledgeDocumentChunkToggleDTO;
import com.hai.aiknowledgebase.dto.KnowledgeDocumentChunkUpdateDTO;
import com.hai.aiknowledgebase.dto.KnowledgeDocumentChunksVO;
import com.hai.aiknowledgebase.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <h2>知识库文档片段管理接口（精细化干预 & 启停控制）</h2>
 *
 * <h3>接口列表</h3>
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/knowledge-documents/chunks/document/{documentId}</td><td>查询已保存片段列表</td></tr>
 *   <tr><td>PUT</td><td>/api/knowledge-documents/chunks/{chunkId}</td><td>编辑单个片段（文本/元数据）</td></tr>
 *   <tr><td>POST</td><td>/api/knowledge-documents/chunks/merge</td><td>合并多个片段</td></tr>
 *   <tr><td>POST</td><td>/api/knowledge-documents/chunks/split</td><td>拆分片段</td></tr>
 *   <tr><td>PUT</td><td>/api/knowledge-documents/chunks/enabled</td><td>批量启停片段（召回过滤）</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge-documents/chunks")
@RequiredArgsConstructor
public class KnowledgeDocumentChunkController {

    private final KnowledgeDocumentService knowledgeDocumentService;

    /**
     * <h3>查询已保存片段列表</h3>
     *
     * <p>返回 {@code knowledge_document_chunk} 表中的片段（含数据库主键与启用状态），
     * 供片段管理页展示。查看操作仅需 VIEWER 角色。</p>
     */
    @GetMapping("/document/{documentId}")
    public Result<KnowledgeDocumentChunksVO> listChunks(@PathVariable Long documentId) {
        log.info("查询片段列表: documentId={}", documentId);
        KnowledgeDocumentChunksVO vo = knowledgeDocumentService.listChunksForDocument(documentId, Role.VIEWER);
        return Result.success(vo);
    }

    /**
     * <h3>编辑单个片段（精细化干预）</h3>
     *
     * <p>修改片段文本或元数据，token 数由服务端自动重算；文档已向量化时
     * 异步重算该片段的向量与 BM25 索引，其余片段不受影响。</p>
     */
    @PutMapping("/{chunkId}")
    public Result<KnowledgeDocumentChunksVO.ChunkItem> updateChunk(
            @PathVariable Long chunkId,
            @RequestBody KnowledgeDocumentChunkUpdateDTO dto) {
        log.info("编辑片段: chunkId={}", chunkId);
        KnowledgeDocumentChunksVO.ChunkItem item = knowledgeDocumentService.updateChunk(chunkId, dto);
        return Result.success(item);
    }

    /**
     * <h3>合并多个片段（精细化干预）</h3>
     *
     * <p>将多个相邻片段合并为单个片段，合并文本为各片段文本的拼接。</p>
     */
    @PostMapping("/merge")
    public Result<KnowledgeDocumentChunksVO.ChunkItem> mergeChunks(
            @RequestBody KnowledgeDocumentChunkMergeDTO dto) {
        log.info("合并片段: documentId={}, chunkIds={}", dto != null ? dto.getDocumentId() : null,
                dto != null ? dto.getChunkIds() : null);
        KnowledgeDocumentChunksVO.ChunkItem item = knowledgeDocumentService.mergeChunks(dto);
        return Result.success(item);
    }

    /**
     * <h3>拆分片段（精细化干预）</h3>
     *
     * <p>将单个片段拆分为多个片段，拆分后的文本由请求体提供。</p>
     */
    @PostMapping("/split")
    public Result<List<KnowledgeDocumentChunksVO.ChunkItem>> splitChunk(
            @RequestBody KnowledgeDocumentChunkSplitDTO dto) {
        log.info("拆分片段: documentId={}, chunkId={}", dto != null ? dto.getDocumentId() : null,
                dto != null ? dto.getChunkId() : null);
        List<KnowledgeDocumentChunksVO.ChunkItem> items = knowledgeDocumentService.splitChunk(dto);
        return Result.success(items);
    }

    /**
     * <h3>批量启停片段（召回过滤）</h3>
     *
     * <p>{@code enabled=false} 时片段在检索时被过滤，{@code enabled=true} 时恢复召回。
     * 向量数据始终保留，仅检索层过滤，零成本切换。</p>
     */
    @PutMapping("/enabled")
    public Result<List<KnowledgeDocumentChunksVO.ChunkItem>> setChunksEnabled(
            @RequestBody KnowledgeDocumentChunkToggleDTO dto) {
        log.info("批量启停片段: chunkIds={}, enabled={}",
                dto != null ? dto.getChunkIds() : null, dto != null ? dto.getEnabled() : null);
        List<KnowledgeDocumentChunksVO.ChunkItem> items = knowledgeDocumentService.setChunksEnabled(dto);
        return Result.success(items);
    }
}