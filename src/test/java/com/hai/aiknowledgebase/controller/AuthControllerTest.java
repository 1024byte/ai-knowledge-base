package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.LoginRequest;
import com.hai.aiknowledgebase.dto.LoginResponse;
import com.hai.aiknowledgebase.entity.User;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.UserMapper;
import com.hai.aiknowledgebase.security.CustomUserDetails;
import com.hai.aiknowledgebase.security.JwtTokenUtil;
import com.hai.aiknowledgebase.service.RefreshTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthController 登录接口单元测试")
class AuthControllerTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtTokenUtil jwtTokenUtil;

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private Authentication authentication;

    @Mock
    private HttpServletRequest httpRequest;

    @InjectMocks
    private AuthController authController;

    private User mockUser;
    private CustomUserDetails mockUserDetails;

    @BeforeEach
    void setUp() {
        mockUser = new User();
        mockUser.setId(1L);
        mockUser.setUsername("admin");
        mockUser.setPassword("$2a$10$hashedPassword");
        mockUser.setEmail("admin@example.com");
        mockUser.setRole("ADMIN");
        mockUser.setStatus("active");

        mockUserDetails = new CustomUserDetails(mockUser);
    }

    @Test
    @DisplayName("正常登录 - 用户名admin密码admin123验证")
    void shouldLoginSuccessfully() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(mockUserDetails);

        when(jwtTokenUtil.generateAccessToken(eq(1L), eq("admin"), eq("ADMIN"), anyString(), eq(0)))
                .thenReturn("mock-access-token-xxx");
        when(jwtTokenUtil.generateRefreshToken(eq(1L), eq("admin"), anyString(), eq(0)))
                .thenReturn("mock-refresh-token-yyy");

        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(httpRequest.getHeader("X-Real-IP")).thenReturn(null);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");

        Result<LoginResponse> result = authController.login(request, httpRequest);

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(0);
        assertThat(result.getMessage()).isEqualTo("success");

        LoginResponse data = result.getData();
        assertThat(data).isNotNull();
        assertThat(data.getAccessToken()).isEqualTo("mock-access-token-xxx");
        assertThat(data.getRefreshToken()).isEqualTo("mock-refresh-token-yyy");
        assertThat(data.getUserId()).isEqualTo(1L);
        assertThat(data.getUsername()).isEqualTo("admin");
        assertThat(data.getRole()).isEqualTo("ADMIN");
        assertThat(data.getDeviceId()).isNotNull();
    }

    @Test
    @DisplayName("登录成功 - 角色为null时默认返回USER")
    void shouldReturnDefaultRoleWhenNull() {
        mockUser.setRole(null);
        mockUserDetails = new CustomUserDetails(mockUser);

        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(mockUserDetails);

        when(jwtTokenUtil.generateAccessToken(eq(1L), eq("admin"), isNull(), anyString(), eq(0)))
                .thenReturn("mock-access-token-xxx");
        when(jwtTokenUtil.generateRefreshToken(eq(1L), eq("admin"), anyString(), eq(0)))
                .thenReturn("mock-refresh-token-yyy");

        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(httpRequest.getHeader("X-Real-IP")).thenReturn(null);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");

        Result<LoginResponse> result = authController.login(request, httpRequest);

        assertThat(result.getData().getRole()).isEqualTo("USER");
    }

    @Test
    @DisplayName("用户名不能为空")
    void shouldFailWhenUsernameIsNull() {
        LoginRequest request = new LoginRequest();
        request.setUsername(null);
        request.setPassword("admin123");

        assertThatThrownBy(() -> authController.login(request, httpRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名不能为空");
    }

    @Test
    @DisplayName("用户名不能为空字符串")
    void shouldFailWhenUsernameIsEmpty() {
        LoginRequest request = new LoginRequest();
        request.setUsername("   ");
        request.setPassword("admin123");

        assertThatThrownBy(() -> authController.login(request, httpRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("用户名不能为空");
    }

    @Test
    @DisplayName("密码不能为空")
    void shouldFailWhenPasswordIsNull() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword(null);

        assertThatThrownBy(() -> authController.login(request, httpRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("密码不能为空");
    }

    @Test
    @DisplayName("密码不能为空字符串")
    void shouldFailWhenPasswordIsEmpty() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("");

        assertThatThrownBy(() -> authController.login(request, httpRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("密码不能为空");
    }

    @Test
    @DisplayName("使用客户端传入的 deviceId")
    void shouldUseClientProvidedDeviceId() {
        LoginRequest request = new LoginRequest();
        request.setUsername("admin");
        request.setPassword("admin123");
        request.setDeviceId("my-custom-device-id");

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(mockUserDetails);

        when(jwtTokenUtil.generateAccessToken(eq(1L), eq("admin"), eq("ADMIN"), eq("my-custom-device-id"), eq(0)))
                .thenReturn("mock-access-token-xxx");
        when(jwtTokenUtil.generateRefreshToken(eq(1L), eq("admin"), eq("my-custom-device-id"), eq(0)))
                .thenReturn("mock-refresh-token-yyy");

        when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
        when(httpRequest.getHeader("X-Real-IP")).thenReturn(null);
        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");

        Result<LoginResponse> result = authController.login(request, httpRequest);

        assertThat(result.getData().getDeviceId()).isEqualTo("my-custom-device-id");
    }
}