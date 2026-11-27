package com.hai.aiknowledgebase.mapper.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * PostgreSQL JSONB 通用类型处理器。
 *
 * <p>写入用 {@code setObject(..., Types.OTHER)} 让驱动按 jsonb 发送，避免
 * "column is of type jsonb but expression is of type character varying" 错误；
 * 读取按字符串取回后用 Jackson 反序列化为目标类型。</p>
 *
 * <p>具体字段请使用 {@link JsonbHandlers} 中携带泛型信息的子类，
 * 否则 {@code List<Long>} 会被反序列化为 {@code List<Integer>} 导致类型转换异常。</p>
 */
public abstract class JsonbTypeHandler<T> extends BaseTypeHandler<T> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final TypeReference<T> type;

    protected JsonbTypeHandler(TypeReference<T> type) {
        this.type = type;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setObject(i, MAPPER.writeValueAsString(parameter), Types.OTHER);
        } catch (Exception e) {
            throw new SQLException("JSONB 序列化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public T getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public T getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public T getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private T parse(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new SQLException("JSONB 反序列化失败: " + e.getMessage(), e);
        }
    }

    /** 供 Service 层复用同一 ObjectMapper 配置（如导出、快照反序列化）。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
