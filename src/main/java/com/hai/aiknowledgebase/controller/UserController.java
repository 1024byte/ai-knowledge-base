package com.hai.aiknowledgebase.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.UserSearchVO;
import com.hai.aiknowledgebase.entity.User;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.UserMapper;
import com.hai.aiknowledgebase.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <h2>用户查询接口</h2>
 *
 * <p>提供用户搜索能力，用于「添加知识库成员」时选择目标用户。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserMapper userMapper;

    /**
     * 按用户名模糊搜索用户。
     *
     * <p>需登录；keyword 为空时返回空列表；仅按 username 模糊匹配，
     * 最多返回 20 条（按创建时间升序），仅返回 id + username。</p>
     *
     * @param keyword 用户名关键字
     * @return 匹配的用户列表
     */
    @GetMapping("/search")
    public Result<List<UserSearchVO>> search(@RequestParam(required = false) String keyword) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (currentUserId == null) {
            throw new BusinessException(401, "未登录");
        }

        if (keyword == null || keyword.isBlank()) {
            return Result.success(List.of());
        }

        String kw = keyword.trim();
        List<User> users = userMapper.selectList(new LambdaQueryWrapper<User>()
                .like(User::getUsername, kw)
                .orderByAsc(User::getCreateTime)
                .last("LIMIT 20"));

        List<UserSearchVO> result = users.stream()
                .map(u -> new UserSearchVO(u.getId(), u.getUsername()))
                .toList();

        log.info("用户搜索: keyword={}, count={}", kw, result.size());
        return Result.success(result);
    }
}