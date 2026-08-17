package com.hai.aiknowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hai.aiknowledgebase.entity.RefreshToken;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {

    @Update("UPDATE refresh_tokens SET version = version + 1 WHERE user_id = #{userId} AND device_id = #{deviceId}")
    int incrementVersion(@Param("userId") Long userId, @Param("deviceId") String deviceId);

    @Update("UPDATE refresh_tokens SET version = version + 1 WHERE user_id = #{userId}")
    int incrementVersionAllDevices(@Param("userId") Long userId);
}