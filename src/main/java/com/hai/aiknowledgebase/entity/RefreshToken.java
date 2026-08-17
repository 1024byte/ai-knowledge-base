package com.hai.aiknowledgebase.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("refresh_tokens")
public class RefreshToken {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String deviceId;

    private String tokenHash;

    private Integer version;

    private java.time.LocalDateTime expiresAt;

    private String deviceName;

    private String loginIp;

    private LocalDateTime lastRefreshTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}