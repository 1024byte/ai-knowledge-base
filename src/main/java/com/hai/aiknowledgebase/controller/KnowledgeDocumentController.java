package com.hai.aiknowledgebase.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.BatchChunkResult;
import com.hai.aiknowledgebase.dto.KnowledgeDocumentChunksSaveDTO;
import com.hai.aiknowledgebase.dto.KnowledgeDocumentChunksVO;
import com.hai.aiknowledgebase.dto.KnowledgeDocumentContentUpdateDTO;
import com.hai.aiknowledgebase.dto.KnowledgeDocumentContentVO;
import com.hai.aiknowledgebase.dto.KnowledgeDocumentUploadVO;
import com.hai.aiknowledgebase.dto.KnowledgeDocumentVO;
import com.hai.aiknowledgebase.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * <h2>知识库文件管理接口</h2>
 *
 * <h3>接口列表</h3>
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>POST</td><td>/api/knowledge-documents/upload</td><td>上传知识库文件</td></tr>
 *   <tr><td>GET</td><td>/api/knowledge-documents</td><td>查询文件列表（支持分页+筛选）</td></tr>
 *   <tr><td>GET</td><td>/api/knowledge-documents/{id}</td><td>获取文件详情</td></tr>
 *   <tr><td>DELETE</td><td>/api/knowledge-documents/{id}</td><td>删除文件（逻辑删除）</td></tr>
 *   <tr><td>POST</td><td>/api/knowledge-documents/{id}/parse</td><td>解析文档为 Markdown</td></tr>
 *   <tr><td>GET</td><td>/api/knowledge-documents/{id}/content</td><td>读取解析后的文档内容</td></tr>
 *   <tr><td>PUT</td><td>/api/knowledge-documents/{id}/content</td><td>保存编辑后的解析内容</td></tr>
 *   <tr><td>GET</td><td>/api/knowledge-documents/{id}/chunks</td><td>文档切片预览</td></tr>
 *   <tr><td>PUT</td><td>/api/knowledge-documents/{id}/chunks</td><td>保存审校后的切片</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge-documents")
@RequiredArgsConstructor
public class KnowledgeDocumentController {

    private final KnowledgeDocumentService knowledgeDocumentService;

    /**
     * <h3>上传知识库文件</h3>
     *
     * <p>支持的文件格式：pdf / doc / docx / txt / md / html / htm / markdown</p>
     * <p>最大文件大小：50MB（可通过 {@code knowledge.upload.max-size-mb} 配置）</p>
     *
     * <h4>请求格式</h4>
     * <p>{@code multipart/form-data}</p>
     *
     * <h4>请求参数</h4>
     * <table>
     *   <tr><th>参数</th><th>类型</th><th>必填</th><th>说明</th></tr>
     *   <tr><td>file</td><td>File</td><td>✓</td><td>文档文件</td></tr>
     *   <tr><td>title</td><td>String</td><td>✓</td><td>文档标题（前端预填，去扩展名）</td></tr>
     *   <tr><td>kbId</td><td>Long</td><td>✓</td><td>所属知识库 ID</td></tr>
     * </table>
     *
     * <h4>请求示例（curl）</h4>
     * <pre>{@code
     * curl -X POST http://localhost:8080/api/knowledge-documents/upload \
     *   -F "file=@产品需求说明书.pdf" \
     *   -F "title=产品需求说明书" \
     *   -F "kbId=1"
     * }</pre>
     *
     * <h4>响应示例</h4>
     * <pre>{@code
     * {
     *   "code": 0,
     *   "message": "success",
     *   "data": {
     *     "id": 123,
     *     "title": "产品需求说明书",
     *     "knowledgeBase": "产品知识库",
     *     "fileName": "产品需求说明书_v2.pdf",
     *     "fileType": "pdf",
     *     "fileSize": 2048000,
     *     "parseStatus": "unparsed",
     *     "vectorStatus": "unvectorized",
     *     "createdAt": "2026-11-04 10:30:00"
     *   }
     * }
     * }</pre>
     *
     * <h4>错误码</h4>
     * <table>
     *   <tr><th>HTTP</th><th>message</th><th>说明</th></tr>
     *   <tr><td>400</td><td>知识库不存在</td><td>kbId 参数未匹配到</td></tr>
     *   <tr><td>400</td><td>不支持的文件格式</td><td>扩展名不在允许列表内</td></tr>
     *   <tr><td>400</td><td>文件大小超过限制（最大 50MB）</td><td>超过 size 上限</td></tr>
     *   <tr><td>400</td><td>标题不超过 100 个字符</td><td>title 长度校验</td></tr>
     * </table>
     *
     * @param file          上传的文档文件
     * @param title         文档标题
     * @param kbId          所属知识库 ID
     * @return 上传结果（含解析/向量化状态）
     */
    @PostMapping("/upload")
    public Result<KnowledgeDocumentUploadVO> upload(@RequestParam("file") MultipartFile file,
                                                      @RequestParam("title") String title,
                                                      @RequestParam("kbId") Long kbId) {
        log.info("收到文件上传请求: title={}, kbId={}, size={}",
                title, kbId, file != null ? file.getSize() : 0);
        KnowledgeDocumentUploadVO vo = knowledgeDocumentService.upload(file, title, kbId);
        return Result.success(vo);
    }

    /**
     * <h3>查询文件列表（支持分页 + 筛选）</h3>
     *
     * <p>同时支持全量查询和分页查询：传入 {@code pageNum} / {@code pageSize} 时返回分页结果，
     * 否则返回全量列表（向后兼容）。{@code kbId} 为可选筛选条件，不传时查询所有知识库的文件。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * // 查询所有知识库的文件
     * GET /api/knowledge-documents
     *
     * // 按知识库筛选
     * GET /api/knowledge-documents?kbId=1
     *
     * // 分页查询（第 1 页，每页 10 条）
     * GET /api/knowledge-documents?kbId=1&pageNum=1&pageSize=10
     *
     * // 分页 + 文件名搜索 + 状态筛选
     * GET /api/knowledge-documents?kbId=1&pageNum=1&pageSize=10&fileName=配置&status=2
     * }</pre>
     *
     * @param kbId     知识库 ID（可选，不传查询所有）
     * @param pageNum  页码（可选，从 1 开始）
     * @param pageSize 每页大小（可选）
     * @param fileName 文件名关键字（可选，模糊匹配）
     * @param status   解析状态（可选：0-待处理/1-处理中/2-已完成/3-失败）
     * @return 全量列表或分页结果
     */
    @GetMapping
    public Result<?> list(@RequestParam(required = false) Long kbId,
                          @RequestParam(required = false) Integer pageNum,
                          @RequestParam(required = false) Integer pageSize,
                          @RequestParam(required = false) String fileName,
                          @RequestParam(required = false) Integer status) {
        boolean usePaging = pageNum != null && pageSize != null;
        log.info("查询知识库文件列表: kbId={}, pageNum={}, pageSize={}, fileName={}, status={}, usePaging={}",
                kbId, pageNum, pageSize, fileName, status, usePaging);

        if (usePaging) {
            Page<KnowledgeDocumentVO> page = knowledgeDocumentService.pageByKbId(
                    kbId, pageNum, pageSize, fileName, status);
            return Result.success(page);
        }

        List<KnowledgeDocumentVO> list = knowledgeDocumentService.listByKbId(kbId);
        return Result.success(list);
    }

    /**
     * <h3>获取文件详情</h3>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * GET /api/knowledge-documents/1
     * }</pre>
     *
     * @param id 文件 ID
     * @return 文件详情
     */
    @GetMapping("/{id}")
    public Result<KnowledgeDocumentVO> getById(@PathVariable Long id) {
        log.info("获取知识库文件详情: id={}", id);
        KnowledgeDocumentVO vo = knowledgeDocumentService.getById(id);
        return Result.success(vo);
    }

    /**
     * <h3>删除文档</h3>
     *
     * <p>逻辑删除数据库记录，同时清理磁盘上的原始文件和解析输出文件。
     * 远端 URL 文件不处理，文件不存在不报错。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * DELETE /api/knowledge-documents/1
     * }</pre>
     *
     * <h4>删除范围</h4>
     * <table>
     *   <tr><th>资源</th><th>路径来源</th><th>行为</th></tr>
 *   <tr><td>数据库记录</td><td>—</td><td>逻辑删除（is_deleted=1）</td></tr>
 *   <tr><td>原始文件</td><td>file_url</td><td>本地路径 → 删除；远端 URL → 跳过</td></tr>
 *   <tr><td>解析输出</td><td>parsed_text_url</td><td>本地路径 → 删除；远端 URL → 跳过</td></tr>
 *   <tr><td>切片数据</td><td>knowledge_document_chunk</td><td>物理删除（级联）</td></tr>
 *   <tr><td>向量数据</td><td>PGVector + BM25</td><td>删除该文档全部向量与关键词索引</td></tr>
 *   <tr><td>哈希记录</td><td>document_hash</td><td>删除秒传去重记录</td></tr>
 * </table>
     *
     * <h4>错误码</h4>
     * <table>
     *   <tr><th>HTTP</th><th>message</th><th>说明</th></tr>
     *   <tr><td>404</td><td>文件不存在</td><td>id 无对应记录</td></tr>
     *   <tr><td>400</td><td>文件已删除</td><td>幂等保护：已逻辑删除的记录不再重复操作</td></tr>
     * </table>
     *
     * @param id 文档 ID
     * @return 被删除的文档信息
     */
    @DeleteMapping("/{id}")
    public Result<KnowledgeDocumentVO> delete(@PathVariable Long id) {
        log.info("收到文档删除请求: id={}", id);
        KnowledgeDocumentVO vo = knowledgeDocumentService.delete(id);
        return Result.success(vo);
    }

    /**
     * <h3>解析文档为 Markdown（异步）</h3>
     *
     * <p>根据文档 ID 启动异步解析任务，立即返回 status=1（处理中）。
     * 前端通过轮询列表或详情接口获取最终状态（2=已完成 / 3=失败）。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * // 解析文档
     * POST /api/knowledge-documents/1/parse
     * }</pre>
     *
     * <h4>响应示例</h4>
     * <pre>{@code
     * {
     *   "code": 0,
     *   "message": "success",
     *   "data": {
     *     "id": 1,
     *     "fileName": "产品需求说明书.pdf",
     *     "fileExt": "pdf",
     *     "fileSize": 2048000,
     *     "fileUrl": "/upload/1/2026-11/doc-abc.pdf",
     *     "parsedTextUrl": "E:\\project\\parse\\1.md",
     *     "status": 2,
     *     "errorMessage": null,
     *     "createdAt": "2026-11-04T10:30:00"
     *   }
     * }
     * }</pre>
     *
     * <h4>状态流转</h4>
     * <table>
     *   <tr><th>解析前 status</th><th>解析成功</th><th>解析失败</th></tr>
     *   <tr><td>0(待处理) / 3(失败)</td><td>→ 2(已完成)</td><td>→ 3(失败)</td></tr>
     *   <tr><td>2(已完成)</td><td colspan="2">允许重新解析，覆盖旧结果</td></tr>
     * </table>
     *
     * <h4>错误码</h4>
     * <table>
     *   <tr><th>HTTP</th><th>message</th><th>说明</th></tr>
     *   <tr><td>404</td><td>文档不存在</td><td>documentId 无对应记录</td></tr>
     *   <tr><td>400</td><td>文档原始文件路径为空</td><td>file_url 字段为 null</td></tr>
     *   <tr><td>400</td><td>文档已向量化，如需重新解析请先重新保存切片</td><td>isVectorized == 2</td></tr>
     *   <tr><td>404</td><td>原始文件不存在</td><td>本地路径下找不到文件</td></tr>
     *   <tr><td>500</td><td>文档解析失败: ...</td><td>解析引擎异常，status 已更新为 3</td></tr>
     * </table>
     *
     * @param id 文档 ID
     * @return 文档详情（status=1，前端展示"解析中"）
     */
    @PostMapping("/{id}/parse")
    public Result<KnowledgeDocumentVO> parse(@PathVariable Long id) {
        log.info("收到文档解析请求: id={}", id);
        KnowledgeDocumentVO vo = knowledgeDocumentService.startParse(id);
        return Result.success(vo);
    }

    /**
     * <h3>读取解析后的文档内容</h3>
     *
     * <p>读取文档已解析的 Markdown 原文，供用户在知识库管理页点击"预览"时渲染。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * GET /api/knowledge-documents/1/content
     * }</pre>
     *
     * <h4>响应示例</h4>
     * <pre>{@code
     * {
     *   "code": 0,
     *   "message": "success",
     *   "data": {
     *     "documentId": 1,
     *     "fileName": "产品需求说明书.pdf",
     *     "content": "# 第一章\n\n时态是表示动作发生时间..."
     *   }
     * }
     * }</pre>
     *
     * <h4>错误码</h4>
     * <table>
     *   <tr><th>HTTP</th><th>message</th><th>说明</th></tr>
     *   <tr><td>404</td><td>文件不存在</td><td>id 无对应记录</td></tr>
     *   <tr><td>400</td><td>请先完成文档解析</td><td>parsedTextUrl 为空</td></tr>
     *   <tr><td>400</td><td>文档未完成解析，无法预览</td><td>status != 2</td></tr>
     *   <tr><td>400</td><td>解析文件已丢失，请重新解析</td><td>parsedTextUrl 指向的文件不存在</td></tr>
     *   <tr><td>500</td><td>读取解析文件失败</td><td>IO 异常</td></tr>
     * </table>
     *
     * @param id 文档 ID
     * @return 解析内容（含文档 ID、文件名、Markdown 原文）
     */
    @GetMapping("/{id}/content")
    public Result<KnowledgeDocumentContentVO> getContent(@PathVariable Long id) {
        log.info("收到读取解析内容请求: id={}", id);
        KnowledgeDocumentContentVO vo = knowledgeDocumentService.getParsedContent(id);
        return Result.success(vo);
    }

    /**
     * <h3>保存编辑后的解析内容</h3>
     *
     * <p>将前端编辑后的 Markdown 原文写回解析文件，并清空该文档已有切片。
     * 已向量化的文档不允许编辑。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * PUT /api/knowledge-documents/1/content
     * Content-Type: application/json
     *
     * {
     *   "content": "# 第一章\n\n编辑后的内容...",
     *   "confirm": true
     * }
     * }</pre>
     *
     * <h4>confirm 确认机制</h4>
     * <p>文档存在切片时，首次提交 confirm=false 会返回提示，前端弹确认框；
     * 用户确认后带 confirm=true 重试，才真正清空切片并保存。</p>
     *
     * <h4>响应示例</h4>
     * <pre>{@code
     * {
     *   "code": 0,
     *   "message": "success",
     *   "data": null
     * }
     * }</pre>
     *
     * <h4>错误码</h4>
     * <table>
     *   <tr><th>HTTP</th><th>message</th><th>说明</th></tr>
     *   <tr><td>404</td><td>文件不存在</td><td>id 无对应记录</td></tr>
     *   <tr><td>400</td><td>文档已向量化，无法编辑</td><td>isVectorized == 2</td></tr>
     *   <tr><td>400</td><td>请先完成文档解析</td><td>parsedTextUrl 为空</td></tr>
     *   <tr><td>400</td><td>文档未完成解析，无法编辑</td><td>status != 2</td></tr>
     *   <tr><td>400</td><td>内容不能为空</td><td>content 为空</td></tr>
     *   <tr><td>400</td><td>解析文件已丢失，请重新解析</td><td>parsedTextUrl 指向的文件不存在</td></tr>
     *   <tr><td>400</td><td>文档已有切片，保存后将清空切片并需重新向量化</td><td>存在切片且 confirm != true</td></tr>
     *   <tr><td>500</td><td>保存解析内容失败</td><td>IO 异常</td></tr>
     * </table>
     *
     * @param id      文档 ID
     * @param request 编辑请求（含 content、confirm）
     * @return 空数据
     */
    @PutMapping("/{id}/content")
    public Result<Void> updateContent(@PathVariable Long id,
                                      @RequestBody KnowledgeDocumentContentUpdateDTO request) {
        log.info("收到保存解析内容请求: id={}, contentLength={}, confirm={}",
                id, request != null && request.getContent() != null ? request.getContent().length() : 0,
                request != null ? request.getConfirm() : null);
        knowledgeDocumentService.updateParsedContent(id, request);
        return Result.success();
    }

    /**
     * <h3>文档切片预览</h3>
     *
     * <p>读取已解析的 Markdown 文件，返回切片后的片段列表。
     * 纯本地 CPU 操作，同步返回，前端无需轮询。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * GET /api/knowledge-documents/1/chunks
     * }</pre>
     *
     * <h4>响应示例</h4>
     * <pre>{@code
     * {
     *   "code": 0,
     *   "message": "success",
     *   "data": {
     *     "documentId": 1,
     *     "fileName": "时态.md",
     *     "totalChunks": 3,
     *     "chunks": [
     *       {
     *         "index": 0,
     *         "text": "**上下文**: 第一章\n\n时态是表示...",
     *         "tokenCount": 520,
     *         "contextPrefix": "第一章",
     *         "metadata": {
     *           "content_type": "text",
     *           "parent_text": "...",
     *           "parent_start": "0",
     *           "parent_end": "2"
     *         }
     *       },
     *       {
     *         "index": 1,
     *         "text": "**上下文**: 第一章 > 1.1 概述\n\n...",
     *         "tokenCount": 400,
     *         "contextPrefix": "第一章 > 1.1 概述",
     *         "metadata": {
     *           "content_type": "text",
     *           "parent_text": "...",
     *           "parent_start": "0",
     *           "parent_end": "2"
     *         }
     *       }
     *     ]
     *   }
     * }
     * }</pre>
     *
     * <h4>错误码</h4>
     * <table>
     *   <tr><th>HTTP</th><th>message</th><th>说明</th></tr>
     *   <tr><td>404</td><td>文件不存在</td><td>id 无对应记录</td></tr>
     *   <tr><td>400</td><td>请先完成文档解析</td><td>parsedTextUrl 为空</td></tr>
     *   <tr><td>400</td><td>文档未完成解析，无法切片</td><td>status != 2</td></tr>
     *   <tr><td>400</td><td>解析文件已丢失，请重新解析</td><td>parsedTextUrl 指向的文件不存在</td></tr>
     *   <tr><td>500</td><td>读取解析文件失败</td><td>IO 异常</td></tr>
     * </table>
     *
     * @param id 文档 ID
     * @return 切片预览结果（含文件名、切片总数、片段列表）
     */
    @GetMapping("/{id}/chunks")
    public Result<KnowledgeDocumentChunksVO> getChunks(@PathVariable Long id) {
        log.info("收到文档切片请求: id={}", id);
        KnowledgeDocumentChunksVO vo = knowledgeDocumentService.chunkDocument(id);
        return Result.success(vo);
    }

    /**
     * <h3>保存审校后的切片（全量覆盖）</h3>
     *
     * <p>前端在切片预览后对切片进行增删改，确认无误后调用此接口保存。
     * 全量覆盖写入，保证与前端展示一致。后续向量化将从此表读取。</p>
     *
     * <p>已向量化的文档重新保存切片时，需前端先带 {@code confirm=false} 提交，
     * 后端返回提示；用户确认后带 {@code confirm=true} 重试，才会真正执行保存
     * 并清理向量化数据（PGVector + BM25 索引）。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * PUT /api/knowledge-documents/1/chunks
     * Content-Type: application/json
     *
     * {
     *   "confirm": true,
     *   "chunks": [
     *     {
     *       "index": 0,
     *       "text": "**上下文**: 第一章\n\n时态是表示动作发生时间...",
     *       "tokenCount": 520,
     *       "contextPrefix": "第一章",
     *       "metadata": {
     *         "content_type": "text",
     *         "parent_text": "...",
     *         "parent_start": "0",
     *         "parent_end": "2"
     *       }
     *     },
     *     {
     *       "index": 1,
     *       "text": "用户手动修改后的文本...",
     *       "tokenCount": 300,
     *       "contextPrefix": "自定义上下文",
     *       "metadata": {}
     *     }
     *   ]
     * }
     * }</pre>
     *
     * <h4>响应示例</h4>
     * <pre>{@code
     * {
     *   "code": 0,
     *   "message": "success",
     *   "data": null
     * }
     * }</pre>
     *
     * <h4>错误码</h4>
     * <table>
     *   <tr><th>HTTP</th><th>message</th><th>说明</th></tr>
     *   <tr><td>404</td><td>文件不存在</td><td>id 无对应记录</td></tr>
     *   <tr><td>400</td><td>文档未完成解析，无法保存切片</td><td>status != 2</td></tr>
     *   <tr><td>400</td><td>切片列表不能为空</td><td>chunks 为空</td></tr>
     *   <tr><td>400</td><td>文档已向量化，重新保存切片将清除向量化数据，需重新向量化</td><td>isVectorized == 2 且 confirm != true</td></tr>
     * </table>
     *
     * @param id 文档 ID
     * @param dto 保存请求（含切片列表与确认标记）
     * @return 空数据
     */
    @PutMapping("/{id}/chunks")
    public Result<Void> saveChunks(@PathVariable Long id,
                                   @RequestBody KnowledgeDocumentChunksSaveDTO dto) {
        List<KnowledgeDocumentChunksVO.ChunkItem> chunks = dto != null ? dto.getChunks() : null;
        boolean confirm = dto != null && Boolean.TRUE.equals(dto.getConfirm());
        log.info("收到保存切片请求: id={}, chunkCount={}, confirm={}",
                id, chunks != null ? chunks.size() : 0, confirm);
        knowledgeDocumentService.saveChunks(id, chunks, confirm);
        return Result.success();
    }

    /**
     * <h3>快速切片并保存（单文档）</h3>
     *
     * <p>跳过人工审校，直接调用切片引擎自动切片并写入 DB。
     * 适合用户无暇逐文档审校时的批量入库场景。</p>
     *
     * <h4>错误码</h4>
     * <table>
     *   <tr><th>HTTP</th><th>message</th><th>说明</th></tr>
     *   <tr><td>404</td><td>文件不存在</td><td>id 无对应记录</td></tr>
     *   <tr><td>400</td><td>文档已向量化，无法快速切片</td><td>isVectorized == 2</td></tr>
     *   <tr><td>400</td><td>文档未完成解析，无法切片</td><td>status != 2</td></tr>
     *   <tr><td>400</td><td>请先完成文档解析</td><td>parsedTextUrl 为空</td></tr>
     *   <tr><td>400</td><td>解析文件已丢失，请重新解析</td><td>解析文件不存在</td></tr>
     * </table>
     *
     * @param id 文档 ID
     * @return 空数据（成功）或异常（失败）
     */
    @PostMapping("/{id}/auto-chunk")
    public Result<Void> autoChunk(@PathVariable Long id) {
        log.info("收到快速切片请求: id={}", id);
        knowledgeDocumentService.autoChunkAndSave(id);
        return Result.success();
    }

    /**
     * <h3>批量快速切片并保存</h3>
     *
     * <p>接受文档 ID 数组，逐个自动切片并落库。单文档失败不中断其他文档。
     * 返回总数、成功数和失败明细。</p>
     *
     * <h4>预检规则</h4>
     * <p>只要列表中存在任意已向量化（isVectorized == 2）的文档，
     * 整个批次直接拒绝，一个都不处理。</p>
     *
     * <h4>失败明细示例</h4>
     * <pre>
     * {
     *   "total": 5,
     *   "success": 3,
     *   "failures": [
     *     {"id": 3, "message": "文档未完成解析，无法切片"},
     *     {"id": 5, "message": "解析文件已丢失，请重新解析"}
     *   ]
     * }
     * </pre>
     *
     * <h4>错误码</h4>
     * <table>
     *   <tr><th>HTTP</th><th>message</th><th>说明</th></tr>
     *   <tr><td>400</td><td>存在已向量化的文档，无法快速切片</td><td>列表中存在 isVectorized == 2 的文档</td></tr>
     * </table>
     *
     * @param ids 文档 ID 列表
     * @return 批量处理结果
     */
    @PostMapping("/auto-chunk")
    public Result<BatchChunkResult> autoChunkBatch(@RequestBody List<Long> ids) {
        log.info("收到批量快速切片请求: count={}, ids={}", ids != null ? ids.size() : 0, ids);
        BatchChunkResult result = knowledgeDocumentService.autoChunkBatch(ids);
        return Result.success(result);
    }

    /**
     * <h3>启动文档向量化</h3>
     *
     * <p>从 {@code knowledge_document_chunk} 表读取用户审校后的切片，嵌入向量化后
     * 存入 PGVector 和 BM25 索引。校验通过后立即返回 {@code status=1}（处理中），
     * 后端异步执行，前端轮询 {@code GET /api/knowledge-documents/{id}} 感知完成。</p>
     *
     * <h4>前置校验</h4>
     * <table>
     *   <tr><td>条件</td><td>HTTP</td><td>message</td></tr>
     *   <tr><td>文档不存在</td><td>404</td><td>文件不存在</td></tr>
     *   <tr><td>status != 2</td><td>400</td><td>文档未完成解析，无法向量化</td></tr>
     *   <tr><td>chunkCount 为空或0</td><td>400</td><td>请先保存切片后再向量化</td></tr>
     *   <tr><td>status == 1（处理中）</td><td>400</td><td>文档正在处理中，请稍后再试</td></tr>
     * </table>
     *
     * @param id 文档 ID
     * @return 文档 VO（status=1，parseStatus=parsed, vectorStatus=unvectorized）
     */
    @PostMapping("/{id}/vectorize")
    public Result<KnowledgeDocumentVO> vectorize(@PathVariable Long id) {
        log.info("收到向量化请求: id={}", id);
        KnowledgeDocumentVO vo = knowledgeDocumentService.startVectorize(id);
        return Result.success(vo);
    }



}