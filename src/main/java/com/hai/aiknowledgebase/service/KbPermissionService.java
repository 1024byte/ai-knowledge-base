package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hai.aiknowledgebase.common.Role;
import com.hai.aiknowledgebase.entity.KbMember;
import com.hai.aiknowledgebase.entity.KnowledgeBase;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.KbMemberMapper;
import com.hai.aiknowledgebase.mapper.KnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <h2>知识库权限服务</h2>
 *
 * <p>负责「用户 ↔ 知识库」的权限判定，是 P0 权限隔离的统一鉴权入口。</p>
 *
 * <h3>角色解析顺序</h3>
 * <ol>
 *   <li>命中 {@code kb_members} 记录 → 返回其角色；</li>
 *   <li>未命中但为知识库创建者（{@code created_by}）→ 视为 OWNER；</li>
 *   <li>未命中但知识库为公开（{@code is_public}）→ 视为 VIEWER；</li>
 *   <li>否则无权限，返回 null。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbPermissionService {

    private final KbMemberMapper kbMemberMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;

    /**
     * 获取用户在某知识库的角色。
     *
     * @param userId 用户 ID
     * @param kbId   知识库 ID
     * @return 角色枚举，无权限或入参为空时返回 null
     */
    public Role getRole(Long userId, Long kbId) {
        if (userId == null || kbId == null) {
            return null;
        }

        KbMember member = kbMemberMapper.selectOne(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getKbId, kbId)
                .eq(KbMember::getUserId, userId));
        if (member != null) {
            return Role.fromCode(member.getRole());
        }

        KnowledgeBase kb = knowledgeBaseMapper.selectById(kbId);
        if (kb == null) {
            return null;
        }
        if (userId.equals(kb.getCreatedBy())) {
            return Role.OWNER;
        }
        if (Boolean.TRUE.equals(kb.getIsPublic())) {
            return Role.VIEWER;
        }
        return null;
    }

    /**
     * 判断用户是否具备指定角色及以上的权限。
     *
     * @param userId       用户 ID
     * @param kbId         知识库 ID
     * @param requiredRole 所需的最低角色
     * @return true 表示权限足够
     */
    public boolean hasRole(Long userId, Long kbId, Role requiredRole) {
        Role role = getRole(userId, kbId);
        return role != null && role.atLeast(requiredRole);
    }

    /**
     * 校验权限，不足时抛出 403 异常。
     *
     * @param userId       用户 ID
     * @param kbId         知识库 ID
     * @param requiredRole 所需的最低角色
     * @throws BusinessException 无权限时抛出（code=403）
     */
    public void checkPermission(Long userId, Long kbId, Role requiredRole) {
        if (!hasRole(userId, kbId, requiredRole)) {
            log.warn("权限校验失败: userId={}, kbId={}, required={}", userId, kbId, requiredRole);
            throw new BusinessException(403, "无权限访问该知识库");
        }
    }

    /**
     * 获取用户可见的知识库 ID 列表。
     *
     * <p>汇总三类来源并去重：{@code kb_members} 命中、公开知识库、创建者兜底。
     * 最后通过 {@code knowledge_base} 主表过滤掉已逻辑删除的知识库
     * （避免残留成员记录指向已删除的知识库）。</p>
     *
     * @param userId 用户 ID
     * @return 可见知识库 ID 列表，未登录或全部不可见时返回空列表
     */
    public List<Long> getVisibleKbIds(Long userId) {
        if (userId == null) {
            return new ArrayList<>();
        }

        Set<Long> kbIdSet = new HashSet<>();

        List<KbMember> members = kbMemberMapper.selectList(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getUserId, userId));
        for (KbMember member : members) {
            kbIdSet.add(member.getKbId());
        }

        List<KnowledgeBase> publicOrOwned = knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KnowledgeBase>()
                        .and(w -> w.eq(KnowledgeBase::getIsPublic, true)
                                .or()
                                .eq(KnowledgeBase::getCreatedBy, userId)));
        for (KnowledgeBase kb : publicOrOwned) {
            kbIdSet.add(kb.getId());
        }

        if (!kbIdSet.isEmpty()) {
            List<KnowledgeBase> validKbs = knowledgeBaseMapper.selectList(
                    new LambdaQueryWrapper<KnowledgeBase>().in(KnowledgeBase::getId, kbIdSet));
            Set<Long> validIds = new HashSet<>();
            for (KnowledgeBase kb : validKbs) {
                validIds.add(kb.getId());
            }
            kbIdSet.retainAll(validIds);
        }

        return new ArrayList<>(kbIdSet);
    }
}