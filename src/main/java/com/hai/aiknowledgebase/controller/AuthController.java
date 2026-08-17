package com.hai.aiknowledgebase.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.*;
import com.hai.aiknowledgebase.entity.RefreshToken;
import com.hai.aiknowledgebase.entity.User;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.UserMapper;
import com.hai.aiknowledgebase.security.CustomUserDetails;
import com.hai.aiknowledgebase.security.JwtTokenUtil;
import com.hai.aiknowledgebase.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtTokenUtil jwtTokenUtil;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest) {
        if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
            throw new BusinessException(400, "用户名不能为空");
        }
        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            throw new BusinessException(400, "密码不能为空");
        }

        String deviceId = request.getDeviceId() != null && !request.getDeviceId().trim().isEmpty()
                ? request.getDeviceId().trim() : UUID.randomUUID().toString();
        String deviceName = request.getDeviceName();
        String loginIp = getClientIp(httpRequest);

        log.info("用户登录: username={}, deviceId={}", request.getUsername(), deviceId);

        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword())
        );

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();
        User user = userDetails.getUser();

        String accessToken = jwtTokenUtil.generateAccessToken(
                user.getId(), user.getUsername(), user.getRole(), deviceId, 0);
        String refreshToken = jwtTokenUtil.generateRefreshToken(
                user.getId(), user.getUsername(), deviceId, 0);

        refreshTokenService.saveOrUpdate(user.getId(), deviceId, refreshToken, 0,
                deviceName, loginIp);

        log.info("用户登录成功: userId={}, username={}, deviceId={}",
                user.getId(), user.getUsername(), deviceId);

        return Result.success(LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .deviceId(deviceId)
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole() != null ? user.getRole() : "USER")
                .build());
    }

    @PostMapping("/register")
    public Result<LoginResponse> register(@RequestBody RegisterRequest request,
                                          HttpServletRequest httpRequest) {
        if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
            throw new BusinessException(400, "用户名不能为空");
        }
        if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
            throw new BusinessException(400, "密码不能为空");
        }
        if (request.getPassword().length() < 6) {
            throw new BusinessException(400, "密码长度至少6位");
        }

        Long count = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, request.getUsername())
        );
        if (count > 0) {
            throw new BusinessException(400, "用户名已存在");
        }

        String deviceId = request.getDeviceId() != null && !request.getDeviceId().trim().isEmpty()
                ? request.getDeviceId().trim() : UUID.randomUUID().toString();
        String loginIp = getClientIp(httpRequest);

        log.info("用户注册: username={}, deviceId={}", request.getUsername(), deviceId);

        User user = new User();
        user.setUsername(request.getUsername());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setEmail(request.getEmail());
        user.setRole("USER");
        user.setStatus("active");
        userMapper.insert(user);

        String accessToken = jwtTokenUtil.generateAccessToken(
                user.getId(), user.getUsername(), user.getRole(), deviceId, 0);
        String refreshToken = jwtTokenUtil.generateRefreshToken(
                user.getId(), user.getUsername(), deviceId, 0);

        refreshTokenService.saveOrUpdate(user.getId(), deviceId, refreshToken, 0,
                request.getDeviceName(), loginIp);

        log.info("用户注册成功: userId={}, username={}, deviceId={}",
                user.getId(), user.getUsername(), deviceId);

        return Result.success(LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .deviceId(deviceId)
                .userId(user.getId())
                .username(user.getUsername())
                .role("USER")
                .build());
    }

    @PostMapping("/refresh")
    public Result<LoginResponse> refresh(@RequestBody RefreshTokenRequest request) {
        if (request.getRefreshToken() == null || !jwtTokenUtil.validateToken(request.getRefreshToken())) {
            throw new BusinessException(401, "RefreshToken无效或已过期");
        }

        Long userId = jwtTokenUtil.getUserId(request.getRefreshToken());
        String deviceId = jwtTokenUtil.getDeviceId(request.getRefreshToken());
        int tokenVer = jwtTokenUtil.getVersion(request.getRefreshToken());

        RefreshToken record = refreshTokenService.findByUserIdAndDeviceIdForRefresh(userId, deviceId);
        if (record == null) {
            throw new BusinessException(401, "RefreshToken不存在或已过期");
        }

        if (record.getVersion() != tokenVer) {
            throw new BusinessException(401, "RefreshToken已失效，请重新登录");
        }

        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        String newAccessToken = jwtTokenUtil.generateAccessToken(
                user.getId(), user.getUsername(), user.getRole(), deviceId, record.getVersion());
        String newRefreshToken = jwtTokenUtil.generateRefreshToken(
                user.getId(), user.getUsername(), deviceId, record.getVersion());

        refreshTokenService.rotateTokenHash(userId, deviceId, newRefreshToken);

        log.info("Token刷新: userId={}, deviceId={}, version={}", userId, deviceId, record.getVersion());

        return Result.success(LoginResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .deviceId(deviceId)
                .userId(user.getId())
                .username(user.getUsername())
                .role(user.getRole() != null ? user.getRole() : "USER")
                .build());
    }

    @GetMapping("/me")
    public Result<UserInfoResponse> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(401, "未登录");
        }

        Long userId = (Long) authentication.getPrincipal();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }

        return Result.success(UserInfoResponse.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .role(user.getRole() != null ? user.getRole() : "USER")
                .build());
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        String token = getTokenFromRequest(request);
        if (token != null && jwtTokenUtil.validateToken(token)) {
            Long userId = jwtTokenUtil.getUserId(token);
            String deviceId = jwtTokenUtil.getDeviceId(token);
            if (deviceId != null) {
                refreshTokenService.revokeDevice(userId, deviceId);
            }
        }

        SecurityContextHolder.clearContext();
        return Result.success();
    }

    @GetMapping("/debug/encode-password")
    public Result<String> encodePassword(@RequestParam String raw) {
        return Result.success(passwordEncoder.encode(raw));
    }

    @GetMapping("/debug/fix-password")
    public Result<String> fixPassword(@RequestParam String username, @RequestParam String raw) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username)
        );
        if (user == null) {
            throw new BusinessException(404, "用户不存在");
        }
        String newHash = passwordEncoder.encode(raw);
        user.setPassword(newHash);
        userMapper.updateById(user);
        return Result.success("密码已更新为 " + raw + " 的BCrypt哈希: " + newHash);
    }

    private String getTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}