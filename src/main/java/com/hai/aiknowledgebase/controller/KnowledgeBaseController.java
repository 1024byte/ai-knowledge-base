package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.KnowledgeBaseRequest;
import com.hai.aiknowledgebase.dto.KnowledgeBaseVO;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.service.KnowledgeBaseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * <h2>知识库管理接口</h2>
 *
 * <h3>接口列表</h3>
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>POST</td><td>/api/knowledge-bases</td><td>创建知识库</td></tr>
 *   <tr><td>PUT</td><td>/api/knowledge-bases/{id}</td><td>更新知识库</td></tr>
 *   <tr><td>GET</td><td>/api/knowledge-bases/{id}</td><td>获取知识库详情</td></tr>
 *   <tr><td>GET</td><td>/api/knowledge-bases</td><td>获取知识库列表</td></tr>
 *   <tr><td>DELETE</td><td>/api/knowledge-bases/{id}</td><td>删除知识库</td></tr>
 *   <tr><td>PATCH</td><td>/api/knowledge-bases/{id}/status</td><td>启用/禁用知识库</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge-bases")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    /**
     * <h3>创建知识库</h3>
     *
     * <p>同时创建基本信息与高级配置，在同一事务中完成。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * POST /api/knowledge-bases
     * {
     *   "name": "Java技术文档",
     *   "description": "Java相关的技术文档知识库",
     *   "chunkLength": 800,
     *   "overlapLength": 80,
     *   "splitStrategy": "RECURSIVE",
     *   "embeddingModel": "text-embedding-3-small",
     *   "retrievalTopK": 5,
     *   "similarityThreshold": 0.7
     * }
     * }</pre>
     *
     * @param request 创建请求
     * @return 创建后的知识库详情
     */
    @PostMapping
    public Result<KnowledgeBaseVO> create(@Valid @RequestBody KnowledgeBaseRequest request) {
        log.info("创建知识库请求: name={}", request.getName());
        KnowledgeBaseVO vo = knowledgeBaseService.create(request);
        return Result.success(vo);
    }

    /**
     * <h3>更新知识库</h3>
     *
     * <p>只更新非 null 字段，支持部分更新。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * PUT /api/knowledge-bases/1
     * {
     *   "name": "Java技术文档v2",
     *   "similarityThreshold": 0.8
     * }
     * }</pre>
     *
     * @param id      知识库 ID
     * @param request 更新请求（部分更新）
     * @return 更新后的知识库详情
     */
    @PutMapping("/{id}")
    public Result<KnowledgeBaseVO> update(@PathVariable Long id,
                                          @Valid @RequestBody KnowledgeBaseRequest request) {
        log.info("更新知识库请求: id={}", id);
        KnowledgeBaseVO vo = knowledgeBaseService.update(id, request);
        return Result.success(vo);
    }

    /**
     * <h3>获取知识库详情</h3>
     *
     * <p>返回知识库基本信息及高级配置。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * GET /api/knowledge-bases/1
     * }</pre>
     *
     * @param id 知识库 ID
     * @return 知识库详情
     */
    @GetMapping("/{id}")
    public Result<KnowledgeBaseVO> getById(@PathVariable Long id) {
        log.info("获取知识库详情: id={}", id);
        KnowledgeBaseVO vo = knowledgeBaseService.getById(id);
        return Result.success(vo);
    }

    /**
     * <h3>获取知识库列表</h3>
     *
     * <p>返回所有未删除的知识库，按更新时间降序排列。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * GET /api/knowledge-bases
     * }</pre>
     *
     * @return 知识库列表
     */
    @GetMapping
    public Result<List<KnowledgeBaseVO>> list() {
        log.info("获取知识库列表");
        List<KnowledgeBaseVO> list = knowledgeBaseService.listAll();
        return Result.success(list);
    }

    /**
     * <h3>删除知识库</h3>
     *
     * <p>逻辑删除，仅标记 {@code is_deleted=1}，数据可恢复。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * DELETE /api/knowledge-bases/1
     * }</pre>
     *
     * @param id 知识库 ID
     * @return 成功无数据
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        log.info("删除知识库: id={}", id);
        knowledgeBaseService.delete(id);
        return Result.success();
    }

    /**
     * <h3>启用/禁用知识库</h3>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * PATCH /api/knowledge-bases/1/status
     * { "status": 0 }
     * }</pre>
     *
     * @param id   知识库 ID
     * @param body 包含 {@code status} 字段，0-禁用，1-启用
     * @return 成功无数据
     */
    @PatchMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
        Integer status = body.get("status");
        if (status == null) {
            throw new BusinessException(400, "status 字段不能为空");
        }
        log.info("切换知识库状态: id={}, status={}", id, status);
        knowledgeBaseService.updateStatus(id, status);
        return Result.success();
    }
}