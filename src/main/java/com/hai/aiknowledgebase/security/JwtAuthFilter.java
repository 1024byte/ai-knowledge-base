package com.hai.aiknowledgebase.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenUtil jwtTokenUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String token = getTokenFromRequest(request);

        if (token != null && jwtTokenUtil.validateToken(token)) {
            try {
                Long userId = jwtTokenUtil.getUserId(token);
                String username = jwtTokenUtil.getUsername(token);
                String role = jwtTokenUtil.getRole(token);
                String deviceId = jwtTokenUtil.getDeviceId(token);
                int ver = jwtTokenUtil.getVersion(token);

                var authority = new SimpleGrantedAuthority("ROLE_" + (role != null ? role : "USER"));

                Map<String, Object> details = new HashMap<>();
                details.put("deviceId", deviceId);
                details.put("ver", ver);

                var authentication = new UsernamePasswordAuthenticationToken(
                        userId, null, Collections.singletonList(authority));
                authentication.setDetails(details);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exception e) {
                log.warn("JWT Token 解析失败: {}", e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    private String getTokenFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}