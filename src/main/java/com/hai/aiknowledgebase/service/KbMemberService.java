package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hai.aiknowledgebase.common.Role;
import com.hai.aiknowledgebase.dto.KbMemberCreateRequest;
import com.hai.aiknowledgebase.dto.KbMemberRequest;
import com.hai.aiknowledgebase.dto.KbMemberVO;
import com.hai.aiknowledgebase.entity.KbMember;
import com.hai.aiknowledgebase.entity.User;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.KbMemberMapper;
import com.hai.aiknowledgebase.mapper.KnowledgeBaseMapper;
import com.hai.aiknowledgebase.mapper.UserMapper;
import com.hai.aiknowledgebase.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * <h2>知识库成员管理服务</h2>
 *
 * <p>负责知识库成员的查询、添加、角色变更与移除。</p>
 *
 * <h3>权限规则</h3>
 * <ul>
 *   <li>查看成员列表：OWNER/ADMIN/EDITOR/VIEWER 均可；</li>
 *   <li>添加/修改/移除：仅 OWNER/ADMIN；</li>
 *   <li>仅 OWNER 可任命 OWNER；</li>
 *   <li>不能移除/降级知识库的最后一名 OWNER。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbMemberService {

    private final KbMemberMapper kbMemberMapper;
    private final UserMapper userMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbPermissionService kbPermissionService;
    private final PasswordEncoder passwordEncoder;

    // ======================== 查询 ========================

    /**
     * 查询知识库成员列表。
     *
     * @param kbId 知识库 ID
     * @return 成员列表（含用户名）
     */
    public List<KbMemberVO> listMembers(Long kbId) {
        Long userId = requireLogin();
        checkKbExists(kbId);
        kbPermissionService.checkPermission(userId, kbId, Role.VIEWER);

        List<KbMember> members = kbMemberMapper.selectList(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getKbId, kbId)
                .orderByAsc(KbMember::getCreatedAt));

        // 批量联查用户名，避免逐条查询
        List<Long> userIds = members.stream().map(KbMember::getUserId).distinct().toList();
        Map<Long, String> usernameMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (User user : userMapper.selectBatchIds(userIds)) {
                usernameMap.put(user.getId(), user.getUsername());
            }
        }

        return members.stream()
                .map(m -> new KbMemberVO(m.getId(), m.getUserId(), usernameMap.get(m.getUserId()), m.getRole(), m.getCreatedAt()))
                .toList();
    }

    // ======================== 添加 ========================

    /**
     * 添加成员；若目标用户已是成员则更新其角色（幂等）。
     *
     * @param kbId    知识库 ID
     * @param request 添加请求（userId + role）
     */
    @Transactional
    public void addMember(Long kbId, KbMemberRequest request) {
        Long operatorId = requireLogin();
        checkManagePermission(operatorId, kbId);
        if (request.getUserId() == null) {
            throw new BusinessException(400, "用户 ID 不能为空");
        }
        checkUserExists(request.getUserId());

        Role targetRole = parseRole(request.getRole());
        Long targetUserId = request.getUserId();

        KbMember existing = findMember(kbId, targetUserId);
        if (existing == null) {
            KbMember member = new KbMember();
            member.setKbId(kbId);
            member.setUserId(targetUserId);
            member.setRole(targetRole.getCode());
            member.setCreatedBy(operatorId);
            kbMemberMapper.insert(member);
            log.info("添加成员成功: kbId={}, userId={}, role={}", kbId, targetUserId, targetRole.getCode());
        } else {
            doUpdateRole(existing, targetRole, operatorId, kbId);
        }
    }

    /**
     * 创建成员：一步完成「创建用户（若不存在）+ 加入知识库」。
     *
     * <p>用户名已存在时复用该用户直接加入（忽略 password）；否则创建新用户
     * （系统角色 USER、状态 active，密码 BCrypt 加密）。</p>
     *
     * @param kbId    知识库 ID
     * @param request 创建请求（username + password + role + email）
     */
    @Transactional
    public void createMember(Long kbId, KbMemberCreateRequest request) {
        Long operatorId = requireLogin();
        checkManagePermission(operatorId, kbId);

        String username = request.getUsername() == null ? null : request.getUsername().trim();
        if (username == null || username.isEmpty()) {
            throw new BusinessException(400, "用户名不能为空");
        }
        Role targetRole = parseRole(request.getRole());

        // 查找或创建用户
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
                .eq(User::getUsername, username));
        Long targetUserId;
        if (user == null) {
            if (request.getPassword() == null || request.getPassword().isEmpty()) {
                throw new BusinessException(400, "密码不能为空");
            }
            if (request.getPassword().length() < 6) {
                throw new BusinessException(400, "密码长度至少6位");
            }
            user = new User();
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(request.getPassword()));
            user.setEmail(request.getEmail());
            user.setRole("USER");
            user.setStatus("active");
            userMapper.insert(user);
            targetUserId = user.getId();
            log.info("创建用户成功: userId={}, username={}", targetUserId, username);
        } else {
            targetUserId = user.getId();
        }

        // 加入成员（幂等：已是成员则更新角色）
        KbMember existing = findMember(kbId, targetUserId);
        if (existing == null) {
            KbMember member = new KbMember();
            member.setKbId(kbId);
            member.setUserId(targetUserId);
            member.setRole(targetRole.getCode());
            member.setCreatedBy(operatorId);
            kbMemberMapper.insert(member);
            log.info("创建成员成功: kbId={}, userId={}, role={}", kbId, targetUserId, targetRole.getCode());
        } else {
            doUpdateRole(existing, targetRole, operatorId, kbId);
        }
    }

    // ======================== 修改角色 ========================

    /**
     * 修改成员角色。
     *
     * @param kbId         知识库 ID
     * @param targetUserId 目标用户 ID
     * @param role         新角色标识
     */
    @Transactional
    public void updateRole(Long kbId, Long targetUserId, String role) {
        Long operatorId = requireLogin();
        checkManagePermission(operatorId, kbId);
        KbMember member = requireMember(kbId, targetUserId);
        doUpdateRole(member, parseRole(role), operatorId, kbId);
    }

    // ======================== 移除 ========================

    /**
     * 移除成员。
     *
     * @param kbId         知识库 ID
     * @param targetUserId 目标用户 ID
     */
    @Transactional
    public void removeMember(Long kbId, Long targetUserId) {
        Long operatorId = requireLogin();
        checkManagePermission(operatorId, kbId);
        KbMember member = requireMember(kbId, targetUserId);

        if (Role.OWNER.getCode().equals(member.getRole())) {
            ensureNotLastOwner(kbId);
        }

        kbMemberMapper.deleteById(member.getId());
        log.info("移除成员成功: kbId={}, userId={}", kbId, targetUserId);
    }

    // ======================== 私有辅助 ========================

    /**
     * 角色变更核心逻辑：越权任命校验 + 最后一名 OWNER 保护 + 落库。
     */
    private void doUpdateRole(KbMember member, Role targetRole, Long operatorId, Long kbId) {
        // 非 OWNER 不能任命 OWNER
        if (targetRole == Role.OWNER && !kbPermissionService.hasRole(operatorId, kbId, Role.OWNER)) {
            throw new BusinessException(403, "仅知识库所有者可任命所有者");
        }

        Role currentRole = Role.fromCode(member.getRole());
        // 降级 OWNER 时校验是否为最后一名 OWNER
        if (currentRole == Role.OWNER && targetRole != Role.OWNER) {
            ensureNotLastOwner(kbId);
        }

        member.setRole(targetRole.getCode());
        kbMemberMapper.updateById(member);
        log.info("更新成员角色成功: kbId={}, userId={}, role={}", kbId, member.getUserId(), targetRole.getCode());
    }

    private Long requireLogin() {
        Long userId = SecurityUtils.getCurrentUserId();
        if (userId == null) {
            throw new BusinessException(401, "未登录");
        }
        return userId;
    }

    private void checkKbExists(Long kbId) {
        if (knowledgeBaseMapper.selectById(kbId) == null) {
            throw new BusinessException(404, "知识库不存在");
        }
    }

    private void checkManagePermission(Long userId, Long kbId) {
        checkKbExists(kbId);
        if (!kbPermissionService.hasRole(userId, kbId, Role.ADMIN)) {
            throw new BusinessException(403, "无权限管理该知识库成员");
        }
    }

    private void checkUserExists(Long userId) {
        if (userMapper.selectById(userId) == null) {
            throw new BusinessException(400, "用户不存在");
        }
    }

    private Role parseRole(String role) {
        Role r = Role.fromCode(role);
        if (r == null) {
            throw new BusinessException(400, "非法的成员角色");
        }
        return r;
    }

    private KbMember findMember(Long kbId, Long userId) {
        return kbMemberMapper.selectOne(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getKbId, kbId)
                .eq(KbMember::getUserId, userId));
    }

    private KbMember requireMember(Long kbId, Long userId) {
        KbMember member = findMember(kbId, userId);
        if (member == null) {
            throw new BusinessException(404, "该成员不存在");
        }
        return member;
    }

    private void ensureNotLastOwner(Long kbId) {
        Long ownerCount = kbMemberMapper.selectCount(new LambdaQueryWrapper<KbMember>()
                .eq(KbMember::getKbId, kbId)
                .eq(KbMember::getRole, Role.OWNER.getCode()));
        if (ownerCount == null || ownerCount <= 1) {
            throw new BusinessException(400, "知识库至少需要保留一名所有者");
        }
    }
}