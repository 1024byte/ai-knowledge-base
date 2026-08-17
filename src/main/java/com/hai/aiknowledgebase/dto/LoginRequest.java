package com.hai.aiknowledgebase.dto;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
    private String deviceId;
    private String deviceName;
}