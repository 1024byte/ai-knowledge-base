package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.KbMemberCreateRequest;
import com.hai.aiknowledgebase.dto.KbMemberRequest;
import com.hai.aiknowledgebase.dto.KbMemberVO;
import com.hai.aiknowledgebase.service.KbMemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <h2>知识库成员管理接口</h2>
 *
 * <h3>接口列表</h3>
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/knowledge-bases/{kbId}/members</td><td>成员列表</td></tr>
 *   <tr><td>POST</td><td>/api/knowledge-bases/{kbId}/members</td><td>添加成员</td></tr>
 *   <tr><td>POST</td><td>/api/knowledge-bases/{kbId}/members/create</td><td>创建成员（创建用户并加入）</td></tr>
 *   <tr><td>PUT</td><td>/api/knowledge-bases/{kbId}/members/{userId}</td><td>修改成员角色</td></tr>
 *   <tr><td>DELETE</td><td>/api/knowledge-bases/{kbId}/members/{userId}</td><td>移除成员</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge-bases/{kbId}/members")
@RequiredArgsConstructor
public class KbMemberController {

    private final KbMemberService kbMemberService;

    /**
     * <h3>获取知识库成员列表</h3>
     *
     * <p>查看成员列表：OWNER/ADMIN/EDITOR/VIEWER 均可。</p>
     *
     * @param kbId 知识库 ID
     * @return 成员列表（含用户名）
     */
    @GetMapping
    public Result<List<KbMemberVO>> listMembers(@PathVariable Long kbId) {
        log.info("获取成员列表: kbId={}", kbId);
        return Result.success(kbMemberService.listMembers(kbId));
    }

    /**
     * <h3>添加成员</h3>
     *
     * <p>重复添加同一用户时更新其角色（幂等）。仅 OWNER/ADMIN 可操作。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * POST /api/knowledge-bases/1/members
     * {
     *   "userId": 10,
     *   "role": "EDITOR"
     * }
     * }</pre>
     *
     * @param kbId    知识库 ID
     * @param request 添加请求（userId + role）
     */
    @PostMapping
    public Result<Void> addMember(@PathVariable Long kbId,
                                  @Valid @RequestBody KbMemberRequest request) {
        log.info("添加成员请求: kbId={}, userId={}, role={}", kbId, request.getUserId(), request.getRole());
        kbMemberService.addMember(kbId, request);
        return Result.success();
    }

    /**
     * <h3>创建成员（创建用户并加入知识库）</h3>
     *
     * <p>一步创建用户（若不存在）并加入知识库。用户名已存在时复用该用户直接加入
     * （此时 {@code password} 可省略）。仅 OWNER/ADMIN 可操作。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * POST /api/knowledge-bases/1/members/create
     * {
     *   "username": "zhangsan",
     *   "password": "123456",
     *   "role": "EDITOR",
     *   "email": "zhangsan@example.com"
     * }
     * }</pre>
     *
     * @param kbId    知识库 ID
     * @param request 创建请求（username + password + role + email）
     */
    @PostMapping("/create")
    public Result<Void> createMember(@PathVariable Long kbId,
                                     @Valid @RequestBody KbMemberCreateRequest request) {
        log.info("创建成员请求: kbId={}, username={}, role={}", kbId, request.getUsername(), request.getRole());
        kbMemberService.createMember(kbId, request);
        return Result.success();
    }

    /**
     * <h3>修改成员角色</h3>
     *
     * <p>目标用户由路径参数提供，仅传新角色。仅 OWNER/ADMIN 可操作。</p>
     *
     * <h4>请求示例</h4>
     * <pre>{@code
     * PUT /api/knowledge-bases/1/members/10
     * {
     *   "role": "ADMIN"
     * }
     * }</pre>
     *
     * @param kbId   知识库 ID
     * @param userId 目标用户 ID
     * @param request 修改请求（role）
     */
    @PutMapping("/{userId}")
    public Result<Void> updateRole(@PathVariable Long kbId,
                                   @PathVariable Long userId,
                                   @Valid @RequestBody KbMemberRequest request) {
        log.info("修改成员角色请求: kbId={}, userId={}, role={}", kbId, userId, request.getRole());
        kbMemberService.updateRole(kbId, userId, request.getRole());
        return Result.success();
    }

    /**
     * <h3>移除成员</h3>
     *
     * <p>不能移除最后一名 OWNER。仅 OWNER/ADMIN 可操作。</p>
     *
     * @param kbId   知识库 ID
     * @param userId 目标用户 ID
     */
    @DeleteMapping("/{userId}")
    public Result<Void> removeMember(@PathVariable Long kbId,
                                     @PathVariable Long userId) {
        log.info("移除成员请求: kbId={}, userId={}", kbId, userId);
        kbMemberService.removeMember(kbId, userId);
        return Result.success();
    }
}