package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hai.aiknowledgebase.common.Role;
import com.hai.aiknowledgebase.dto.KnowledgeBaseRequest;
import com.hai.aiknowledgebase.dto.KnowledgeBaseVO;
import com.hai.aiknowledgebase.entity.ChatHistory;
import com.hai.aiknowledgebase.entity.KbMember;
import com.hai.aiknowledgebase.entity.KnowledgeBase;
import com.hai.aiknowledgebase.entity.KnowledgeBaseConfig;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.ChatHistoryMapper;
import com.hai.aiknowledgebase.mapper.KbMemberMapper;
import com.hai.aiknowledgebase.mapper.KnowledgeBaseConfigMapper;
import com.hai.aiknowledgebase.mapper.KnowledgeBaseMapper;
import com.hai.aiknowledgebase.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * <h2>知识库管理服务</h2>
 *
 * <p>负责知识库的全生命周期管理，包括创建、查询、更新、删除以及状态切换。</p>
 *
 * <h3>表关系</h3>
 * <p>{@code knowledge_base} 与 {@code knowledge_base_config} 是一对一关系，
 * 创建和更新时需在同一个事务中操作两张表。</p>
 *
 * <h3>逻辑删除</h3>
 * <p>删除操作使用 MyBatis-Plus 的 {@code @TableLogic}，仅标记 {@code is_deleted=1}，
 * 查询时自动过滤已删除记录。</p>
 *
 * <h3>名称唯一性</h3>
 * <p>数据库层面有部分唯一索引 {@code uk_kb_name}（WHERE is_deleted=0），
 * 确保未删除的知识库名称唯一。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConfigMapper knowledgeBaseConfigMapper;
    private final KbMemberMapper kbMemberMapper;
    private final KbPermissionService kbPermissionService;
    private final ChatHistoryMapper chatHistoryMapper;

    /** 文档级联清理服务（@Lazy 打破与 KnowledgeDocumentService 的循环依赖） */
    @Lazy
    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    // ======================== 创建 ========================

    /**
     * <h3>创建知识库</h3>
     *
     * <p>在同一事务中创建 {@code knowledge_base} 和 {@code knowledge_base_config} 两条记录。</p>
     *
     * <h4>默认值</h4>
     * <table>
     *   <tr><th>参数</th><th>未传时的默认值</th></tr>
     *   <tr><td>splitStrategy</td><td>RECURSIVE</td></tr>
     *   <tr><td>embeddingModel</td><td>text-embedding-3-small</td></tr>
     *   <tr><td>retrievalTopK</td><td>5</td></tr>
     *   <tr><td>similarityThreshold</td><td>0.7</td></tr>
     *   <tr><td>status</td><td>1（启用）</td></tr>
     * </table>
     *
     * @param request 知识库创建请求（含基本信息和高级配置）
     * @return 创建的知识库详情
     * @throws BusinessException 名称已存在时抛出
     */
    @Transactional
    public KnowledgeBaseVO create(KnowledgeBaseRequest request) {
        log.info("创建知识库: name={}", request.getName());

        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }

        KnowledgeBase kb = new KnowledgeBase();
        kb.setName(request.getName());
        kb.setDescription(request.getDescription());
        kb.setChunkLength(request.getChunkLength());
        kb.setOverlapLength(request.getOverlapLength());
        kb.setStatus(1);
        kb.setCreatedBy(userId);
        kb.setUpdatedAt(LocalDateTime.now());
        kb.setCreatedAt(LocalDateTime.now());

        try {
            knowledgeBaseMapper.insert(kb);
        } catch (Exception e) {
            log.error("创建知识库失败，名称可能重复: {}", request.getName(), e);
            throw new BusinessException(400, "知识库名称已存在");
        }

        KnowledgeBaseConfig config = new KnowledgeBaseConfig();
        config.setKbId(kb.getId());
        config.setSplitStrategy(request.getSplitStrategy() != null ? request.getSplitStrategy() : "RECURSIVE");
        config.setEmbeddingModel(request.getEmbeddingModel() != null ? request.getEmbeddingModel() : "text-embedding-3-small");
        config.setRetrievalTopK(request.getRetrievalTopK() != null ? request.getRetrievalTopK() : 5);
        config.setSimilarityThreshold(request.getSimilarityThreshold() != null ? request.getSimilarityThreshold() : 0.7);
        config.setUpdatedAt(LocalDateTime.now());
        config.setCreatedAt(LocalDateTime.now());
        knowledgeBaseConfigMapper.insert(config);

        KbMember owner = new KbMember();
        owner.setKbId(kb.getId());
        owner.setUserId(userId);
        owner.setRole(Role.OWNER.getCode());
        owner.setCreatedBy(userId);
        owner.setCreatedAt(LocalDateTime.now());
        owner.setUpdatedAt(LocalDateTime.now());
        kbMemberMapper.insert(owner);

        log.info("知识库创建成功: id={}, name={}", kb.getId(), kb.getName());
        return getById(kb.getId());
    }

    // ======================== 更新 ========================

    /**
     * <h3>更新知识库（基本信息 + 高级配置）</h3>
     *
     * <p>在同一事务中更新两张表。只更新请求中非 null 的字段，null 字段保持原值不变。</p>
     *
     * @param id      知识库 ID
     * @param request 更新请求（非 null 字段会被更新）
     * @return 更新后的知识库详情
     * @throws BusinessException 知识库不存在或已删除时抛出
     */
    @Transactional
    public KnowledgeBaseVO update(Long id, KnowledgeBaseRequest request) {
        log.info("更新知识库: id={}", id);

        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) {
            throw new BusinessException(404, "知识库不存在");
        }
        kbPermissionService.checkPermission(SecurityUtils.getCurrentUserId(), id, Role.ADMIN);

        if (request.getName() != null) kb.setName(request.getName());
        if (request.getDescription() != null) kb.setDescription(request.getDescription());
        if (request.getChunkLength() != null) kb.setChunkLength(request.getChunkLength());
        if (request.getOverlapLength() != null) kb.setOverlapLength(request.getOverlapLength());

        try {
            knowledgeBaseMapper.updateById(kb);
        } catch (Exception e) {
            log.error("更新知识库失败，名称可能重复: {}", request.getName(), e);
            throw new BusinessException(400, "知识库名称已存在");
        }

        KnowledgeBaseConfig config = knowledgeBaseConfigMapper.selectById(id);
        if (config != null) {
            if (request.getSplitStrategy() != null) config.setSplitStrategy(request.getSplitStrategy());
            if (request.getEmbeddingModel() != null) config.setEmbeddingModel(request.getEmbeddingModel());
            if (request.getRetrievalTopK() != null) config.setRetrievalTopK(request.getRetrievalTopK());
            if (request.getSimilarityThreshold() != null) config.setSimilarityThreshold(request.getSimilarityThreshold());
            knowledgeBaseConfigMapper.updateById(config);
        }

        log.info("知识库更新成功: id={}", id);
        return getById(id);
    }

    // ======================== 查询 ========================

    /**
     * <h3>根据名称查询知识库</h3>
     *
     * <p>用于文件上传场景，前端通过知识库名称关联文件。</p>
     *
     * @param name 知识库名称
     * @return 知识库实体（null 表示不存在）
     */
    public KnowledgeBase getByName(String name) {
        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeBase::getName, name).last("LIMIT 1");
        return knowledgeBaseMapper.selectOne(wrapper);
    }

    /**
     * <h3>根据 ID 获取知识库实体</h3>
     *
     * <p>直接返回 {@link KnowledgeBase} 实体，供内部服务调用（非 VO 场景）。
     * 不存在的知识库或已逻辑删除的均返回 {@code null}。</p>
     *
     * @param id 知识库 ID
     * @return 知识库实体，不存在时返回 {@code null}
     */
    public KnowledgeBase findById(Long id) {
        return knowledgeBaseMapper.selectById(id);
    }

    /**
     * <h3>根据 ID 获取知识库详情</h3>
     *
     * <p>合并 {@code knowledge_base} 与 {@code knowledge_base_config} 两张表的数据。</p>
     *
     * @param id 知识库 ID
     * @return 知识库详情 VO（含高级配置）
     * @throws BusinessException 知识库不存在或已删除时抛出
     */
    public KnowledgeBaseVO getById(Long id) {
        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) {
            throw new BusinessException(404, "知识库不存在");
        }
        kbPermissionService.checkPermission(SecurityUtils.getCurrentUserId(), id, Role.VIEWER);

        KnowledgeBaseConfig config = knowledgeBaseConfigMapper.selectById(id);

        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(kb.getId());
        vo.setName(kb.getName());
        vo.setDescription(kb.getDescription());
        vo.setChunkLength(kb.getChunkLength());
        vo.setOverlapLength(kb.getOverlapLength());
        vo.setStatus(kb.getStatus());
        vo.setCreatedAt(kb.getCreatedAt());
        vo.setUpdatedAt(kb.getUpdatedAt());

        if (config != null) {
            vo.setSplitStrategy(config.getSplitStrategy());
            vo.setEmbeddingModel(config.getEmbeddingModel());
            vo.setRetrievalTopK(config.getRetrievalTopK());
            vo.setSimilarityThreshold(config.getSimilarityThreshold());
        }

        return vo;
    }

    /**
     * <h3>获取所有知识库列表（不含高级配置）</h3>
     *
     * <p>按更新时间降序排列，自动过滤已删除记录。</p>
     *
     * @return 知识库详情列表
     */
    public List<KnowledgeBaseVO> listAll() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<Long> visibleKbIds = kbPermissionService.getVisibleKbIds(userId);
        if (visibleKbIds.isEmpty()) {
            return List.of();
        }

        LambdaQueryWrapper<KnowledgeBase> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(KnowledgeBase::getId, visibleKbIds)
                .orderByDesc(KnowledgeBase::getUpdatedAt);
        List<KnowledgeBase> kbList = knowledgeBaseMapper.selectList(wrapper);

        return kbList.stream().map(kb -> {
            KnowledgeBaseConfig config = knowledgeBaseConfigMapper.selectById(kb.getId());

            KnowledgeBaseVO vo = new KnowledgeBaseVO();
            vo.setId(kb.getId());
            vo.setName(kb.getName());
            vo.setDescription(kb.getDescription());
            vo.setChunkLength(kb.getChunkLength());
            vo.setOverlapLength(kb.getOverlapLength());
            vo.setStatus(kb.getStatus());
            vo.setCreatedAt(kb.getCreatedAt());
            vo.setUpdatedAt(kb.getUpdatedAt());

            if (config != null) {
                vo.setSplitStrategy(config.getSplitStrategy());
                vo.setEmbeddingModel(config.getEmbeddingModel());
                vo.setRetrievalTopK(config.getRetrievalTopK());
                vo.setSimilarityThreshold(config.getSimilarityThreshold());
            }

            return vo;
        }).collect(Collectors.toList());
    }

    // ======================== 删除 ========================

    /**
     * <h3>逻辑删除知识库（S8：级联清理关联数据）</h3>
     *
     * <p>使用 MyBatis-Plus 逻辑删除，自动设置 {@code is_deleted=1}。</p>
     *
     * <p>在同一个事务中级联清理以下关联数据（数据库操作原子性，失败整体回滚）：</p>
     * <ol>
     *   <li>文档及派生资源（切片、向量、BM25 索引、文件、哈希）—— 文档逻辑删除，其余物理删除</li>
     *   <li>会话历史（{@code chat_history}）—— 物理删除</li>
     *   <li>成员关系（{@code kb_members}）—— 物理删除</li>
     * </ol>
     * <p>{@code knowledge_base_config} 记录不会被删除（保留历史配置）。</p>
     *
     * @param id 知识库 ID
     * @throws BusinessException 知识库不存在或已删除时抛出
     */
    @Transactional
    public void delete(Long id) {
        log.info("删除知识库: id={}", id);

        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) {
            throw new BusinessException(404, "知识库不存在");
        }
        kbPermissionService.checkPermission(SecurityUtils.getCurrentUserId(), id, Role.OWNER);

        // 1. 级联清理文档及派生资源（复用 KnowledgeDocumentService.deleteByKbId）
        knowledgeDocumentService.deleteByKbId(id);

        // 2. 清理该知识库下的会话历史（chat_history 物理删除）
        int chatDeleted = chatHistoryMapper.delete(
                new LambdaQueryWrapper<ChatHistory>().eq(ChatHistory::getKbId, id));
        log.info("级联删除会话历史: kbId={}, count={}", id, chatDeleted);

        // 3. 清理成员关系（kb_members 物理删除）
        int memberDeleted = kbMemberMapper.delete(
                new LambdaQueryWrapper<KbMember>().eq(KbMember::getKbId, id));
        log.info("级联删除成员关系: kbId={}, count={}", id, memberDeleted);

        // 4. 最后逻辑删除主表
        knowledgeBaseMapper.deleteById(id);
        log.info("知识库已逻辑删除: id={}, name={}", id, kb.getName());
    }

    // ======================== 状态切换 ========================

    /**
     * <h3>启用/禁用知识库</h3>
     *
     * @param id     知识库 ID
     * @param status 目标状态：0-禁用，1-启用
     * @throws BusinessException 知识库不存在或已删除时抛出
     */
    public void updateStatus(Long id, Integer status) {
        log.info("切换知识库状态: id={}, status={}", id, status);

        if (status != 0 && status != 1) {
            throw new BusinessException(400, "状态值无效，仅支持 0（禁用）或 1（启用）");
        }

        KnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null) {
            throw new BusinessException(404, "知识库不存在");
        }
        kbPermissionService.checkPermission(SecurityUtils.getCurrentUserId(), id, Role.ADMIN);

        kb.setStatus(status);
        knowledgeBaseMapper.updateById(kb);

        log.info("知识库状态已切换: id={}, name={}, status={}", id, kb.getName(), status);
    }
}