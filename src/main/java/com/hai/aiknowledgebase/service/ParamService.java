package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hai.aiknowledgebase.common.Role;
import com.hai.aiknowledgebase.dto.ParamItemVO;
import com.hai.aiknowledgebase.entity.ParamConfig;
import com.hai.aiknowledgebase.entity.ParamHistory;
import com.hai.aiknowledgebase.entity.ParamMetadata;
import com.hai.aiknowledgebase.entity.User;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.KnowledgeBaseMapper;
import com.hai.aiknowledgebase.mapper.ParamConfigMapper;
import com.hai.aiknowledgebase.mapper.ParamHistoryMapper;
import com.hai.aiknowledgebase.mapper.ParamMetadataMapper;
import com.hai.aiknowledgebase.mapper.UserMapper;
import com.hai.aiknowledgebase.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <h2>参数管理服务</h2>
 *
 * <p>实现检索调优参数「调参 → 保存草稿 → 审核应用 → 生产生效」的闭环核心逻辑：</p>
 * <ul>
 *   <li><b>参数解析</b>：全局已应用 &gt; 元数据默认值（参数已全局化，不再支持 KB 级覆盖），结果带 30s TTL 缓存</li>
 *   <li><b>save</b>：保存/更新草稿（登录即可）</li>
 *   <li><b>apply</b>：草稿置为已应用（事务 + 删除旧 APPLIED + 写历史 + 缓存失效）</li>
 *   <li><b>rollback</b>：回退到默认值（删除该参数在本作用域的所有配置）</li>
 *   <li><b>reset</b>：清空本作用域下所有配置</li>
 * </ul>
 *
 * <h3>作用域约定</h3>
 * <p>{@code kbId == null} 表示全局（GLOBAL）作用域，{@code kbId != null} 表示知识库级（KB）覆盖。</p>
 *
 * <h3>缓存策略</h3>
 * <p>解析结果缓存 30s；{@code apply/rollback/reset} 后主动失效：
 * 全局变更会清空整个缓存（影响所有 KB 的兜底解析），KB 变更仅失效该 KB 的缓存。
 * 加载时使用双重检查锁防缓存击穿。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParamService {

    public static final String SCOPE_GLOBAL = "GLOBAL";
    public static final String SCOPE_KB = "KB";
    public static final String STATE_DRAFT = "DRAFT";
    public static final String STATE_APPLIED = "APPLIED";
    public static final String ACTION_SAVE = "SAVE";
    public static final String ACTION_APPLY = "APPLY";
    public static final String ACTION_ROLLBACK = "ROLLBACK";
    public static final String ACTION_RESET = "RESET";

    private final ParamMetadataMapper paramMetadataMapper;
    private final ParamConfigMapper paramConfigMapper;
    private final ParamHistoryMapper paramHistoryMapper;
    private final UserMapper userMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KbPermissionService kbPermissionService;

    /** 参数解析缓存：30s TTL，apply/rollback/reset 后主动失效 */
    private final Cache<String, Map<String, String>> resolvedCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    /** 双重检查锁，防缓存击穿 */
    private final Object resolveLock = new Object();

    // ==================== 参数解析（供检索链消费） ====================

    /**
     * <h3>解析某作用域下的生效参数</h3>
     *
     * <p>解析顺序：全局已应用 &gt; 元数据默认值。
     * 参数已全局化，KB 级历史配置不再参与解析；{@code kbId} 参数保留以便后续扩展。</p>
     *
     * @param kbId 知识库 ID，null 表示全局作用域
     * @return 生效参数映射（不可变语义，请勿修改）
     */
    public Map<String, String> resolveAll(Long kbId) {
        String cacheKey = cacheKey(kbId);
        Map<String, String> cached = resolvedCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        synchronized (resolveLock) {
            cached = resolvedCache.getIfPresent(cacheKey);
            if (cached != null) {
                return cached;
            }
            Map<String, String> resolved = loadResolved(kbId);
            resolvedCache.put(cacheKey, resolved);
            return resolved;
        }
    }

    private Map<String, String> loadResolved(Long kbId) {
        Map<String, String> resolved = new LinkedHashMap<>();
        // 1. 元数据默认值兜底
        for (ParamMetadata meta : paramMetadataMapper.selectList(null)) {
            resolved.put(meta.getParamKey(), meta.getDefaultValue());
        }
        // 2. 全局已应用覆盖（参数已全局化，不再读取 KB 级覆盖）
        for (ParamConfig cfg : listApplied(SCOPE_GLOBAL, null)) {
            resolved.put(cfg.getParamKey(), cfg.getParamValue());
        }
        return resolved;
    }

    private List<ParamConfig> listApplied(String scopeType, Long kbId) {
        LambdaQueryWrapper<ParamConfig> wrapper = new LambdaQueryWrapper<ParamConfig>()
                .eq(ParamConfig::getScopeType, scopeType)
                .eq(ParamConfig::getState, STATE_APPLIED);
        if (kbId == null) {
            wrapper.isNull(ParamConfig::getKbId);
        } else {
            wrapper.eq(ParamConfig::getKbId, kbId);
        }
        return paramConfigMapper.selectList(wrapper);
    }

    /**
     * <h3>变更后缓存失效</h3>
     *
     * <p>全局变更影响所有 KB 的兜底解析结果，需清空整个缓存；
     * KB 变更仅影响该 KB，失效对应 key。</p>
     */
    public void evictAfterChange(Long kbId) {
        if (kbId == null) {
            resolvedCache.invalidateAll();
        } else {
            resolvedCache.invalidate(cacheKey(kbId));
        }
    }

    // ==================== 查询 ====================

    /**
     * <h3>查询参数列表（含元数据 + 草稿 + 已应用 + 生效值）</h3>
     *
     * @param kbId 知识库 ID，null 表示全局作用域
     */
    public List<ParamItemVO> list(Long kbId) {
        requireLogin();
        String scopeType = scopeTypeOf(kbId);

        Map<String, String> effective = resolveAll(kbId);
        Map<String, String> applied = new HashMap<>();
        Map<String, String> draft = new HashMap<>();

        List<ParamConfig> configs = listConfigs(scopeType, kbId, null);
        for (ParamConfig c : configs) {
            if (STATE_APPLIED.equals(c.getState())) {
                applied.put(c.getParamKey(), c.getParamValue());
            } else {
                draft.put(c.getParamKey(), c.getParamValue());
            }
        }

        List<ParamMetadata> metas = paramMetadataMapper.selectList(
                new LambdaQueryWrapper<ParamMetadata>().orderByAsc(ParamMetadata::getSortOrder));
        List<ParamItemVO> items = new ArrayList<>();
        for (ParamMetadata m : metas) {
            items.add(ParamItemVO.builder()
                    .paramKey(m.getParamKey())
                    .paramName(m.getParamName())
                    .paramType(m.getParamType())
                    .defaultValue(m.getDefaultValue())
                    .minValue(m.getMinValue())
                    .maxValue(m.getMaxValue())
                    .category(m.getCategory())
                    .description(m.getDescription())
                    .sortOrder(m.getSortOrder())
                    .appliedValue(applied.get(m.getParamKey()))
                    .draftValue(draft.get(m.getParamKey()))
                    .effectiveValue(effective.get(m.getParamKey()))
                    .build());
        }
        return items;
    }

    /**
     * <h3>查询操作历史</h3>
     *
     * @param kbId     知识库 ID，null 表示全局作用域
     * @param paramKey 参数键（可空，空表示查询该作用域下全部历史）
     */
    public List<ParamHistory> history(Long kbId, String paramKey) {
        requireLogin();
        String scopeType = scopeTypeOf(kbId);
        LambdaQueryWrapper<ParamHistory> wrapper = new LambdaQueryWrapper<ParamHistory>()
                .eq(ParamHistory::getScopeType, scopeType)
                .eq(paramKey != null && !paramKey.isBlank(), ParamHistory::getParamKey, paramKey)
                .orderByDesc(ParamHistory::getOperateTime);
        if (kbId == null) {
            wrapper.isNull(ParamHistory::getKbId);
        } else {
            wrapper.eq(ParamHistory::getKbId, kbId);
        }
        return paramHistoryMapper.selectList(wrapper);
    }

    // ==================== 保存草稿 ====================

    /**
     * <h3>保存草稿（登录即可）</h3>
     *
     * <p>对 (param_key, scope_type, kb_id) 的草稿做 upsert：已存在则更新，否则新增。
     * 不触发缓存失效（草稿不影响生产生效，直到 apply）。</p>
     */
    public void save(Long kbId, String paramKey, String paramValue) {
        Long userId = requireLogin();
        String scopeType = scopeTypeOf(kbId);
        if (kbId != null) {
            throw new BusinessException(400, "当前版本仅支持全局参数配置，不支持按知识库配置");
        }
        ParamMetadata meta = requireMetadata(paramKey);
        validateValue(meta, paramValue);

        ParamConfig draft = findConfig(scopeType, kbId, paramKey, STATE_DRAFT);
        String oldValue = draft == null ? null : draft.getParamValue();

        if (draft == null) {
            draft = new ParamConfig();
            draft.setParamKey(paramKey);
            draft.setScopeType(scopeType);
            draft.setKbId(kbId);
            draft.setParamValue(paramValue.trim());
            draft.setState(STATE_DRAFT);
            draft.setCreatedBy(userId);
            draft.setUpdatedBy(userId);
            paramConfigMapper.insert(draft);
        } else {
            draft.setParamValue(paramValue.trim());
            draft.setUpdatedBy(userId);
            paramConfigMapper.updateById(draft);
        }

        writeHistory(paramKey, scopeType, kbId, oldValue, paramValue.trim(), ACTION_SAVE, userId);
        log.info("保存参数草稿: kbId={}, key={}, {} -> {}", kbId, paramKey, oldValue, paramValue.trim());
    }

    // ==================== 应用 ====================

    /**
     * <h3>应用草稿（草稿 → 已应用）</h3>
     *
     * <p>事务内完成：写历史 → 删除旧 APPLIED → 草稿置为 APPLIED，随后失效缓存。
     * DB 层的部分唯一索引 {@code uk_param_config_applied} 兜底并发安全。</p>
     */
    @Transactional
    public void apply(Long kbId, String paramKey) {
        Long userId = requireLogin();
        String scopeType = scopeTypeOf(kbId);
        requireAdminForScope(userId, kbId);
        requireMetadata(paramKey);

        ParamConfig draft = findConfig(scopeType, kbId, paramKey, STATE_DRAFT);
        if (draft == null) {
            throw new BusinessException(400, "该参数无待应用的草稿");
        }

        ParamConfig applied = findConfig(scopeType, kbId, paramKey, STATE_APPLIED);
        String oldValue = applied == null ? null : applied.getParamValue();

        if (applied != null) {
            paramConfigMapper.deleteById(applied.getId());
        }

        draft.setState(STATE_APPLIED);
        draft.setUpdatedBy(userId);
        paramConfigMapper.updateById(draft);

        writeHistory(paramKey, scopeType, kbId, oldValue, draft.getParamValue(), ACTION_APPLY, userId);
        evictAfterChange(kbId);
        log.info("应用参数: kbId={}, key={}, {} -> {}", kbId, paramKey, oldValue, draft.getParamValue());
    }

    // ==================== 回退 / 重置 ====================

    /**
     * <h3>回退参数到默认值</h3>
     *
     * <p>删除该参数在本作用域下的所有配置（草稿 + 已应用），使其回到
     * 「全局默认 / 全局已应用」的兜底值。</p>
     */
    @Transactional
    public void rollback(Long kbId, String paramKey) {
        Long userId = requireLogin();
        String scopeType = scopeTypeOf(kbId);
        requireAdminForScope(userId, kbId);
        ParamMetadata meta = requireMetadata(paramKey);

        List<ParamConfig> configs = listConfigs(scopeType, kbId, paramKey);
        String appliedValue = configs.stream()
                .filter(c -> STATE_APPLIED.equals(c.getState()))
                .map(ParamConfig::getParamValue)
                .findFirst()
                .orElse(null);

        for (ParamConfig c : configs) {
            paramConfigMapper.deleteById(c.getId());
        }

        writeHistory(paramKey, scopeType, kbId, appliedValue, meta.getDefaultValue(), ACTION_ROLLBACK, userId);
        evictAfterChange(kbId);
        log.info("回退参数: kbId={}, key={}, {} -> 默认值 {}", kbId, paramKey, appliedValue, meta.getDefaultValue());
    }

    /**
     * <h3>重置本作用域下所有参数</h3>
     *
     * <p>清空本作用域的全部配置（草稿 + 已应用），逐条写历史。</p>
     */
    @Transactional
    public void reset(Long kbId) {
        Long userId = requireLogin();
        String scopeType = scopeTypeOf(kbId);
        requireAdminForScope(userId, kbId);

        List<ParamConfig> configs = listConfigs(scopeType, kbId, null);
        for (ParamConfig c : configs) {
            paramConfigMapper.deleteById(c.getId());
            writeHistory(c.getParamKey(), scopeType, kbId, c.getParamValue(), null, ACTION_RESET, userId);
        }

        evictAfterChange(kbId);
        log.info("重置参数: kbId={}, 清除 {} 条配置", kbId, configs.size());
    }

    // ==================== 内部工具 ====================

    private String scopeTypeOf(Long kbId) {
        return kbId == null ? SCOPE_GLOBAL : SCOPE_KB;
    }

    private String cacheKey(Long kbId) {
        return kbId == null ? "global" : "kb:" + kbId;
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

    private boolean isGlobalAdmin(Long userId) {
        if (userId == null) {
            return false;
        }
        User user = userMapper.selectById(userId);
        return user != null && "ADMIN".equals(user.getRole());
    }

    /**
     * apply/rollback/reset 的权限校验：全局作用域需全局管理员，
     * KB 作用域需「全局管理员 或 知识库 ADMIN 及以上」。
     */
    private void requireAdminForScope(Long userId, Long kbId) {
        if (kbId == null) {
            if (!isGlobalAdmin(userId)) {
                throw new BusinessException(403, "无权限操作全局参数，需全局管理员");
            }
        } else {
            checkKbExists(kbId);
            if (!isGlobalAdmin(userId) && !kbPermissionService.hasRole(userId, kbId, Role.ADMIN)) {
                throw new BusinessException(403, "无权限操作该知识库参数，需知识库管理员");
            }
        }
    }

    private ParamMetadata requireMetadata(String paramKey) {
        ParamMetadata meta = paramMetadataMapper.selectOne(new LambdaQueryWrapper<ParamMetadata>()
                .eq(ParamMetadata::getParamKey, paramKey));
        if (meta == null) {
            throw new BusinessException(400, "参数不存在: " + paramKey);
        }
        return meta;
    }

    private ParamConfig findConfig(String scopeType, Long kbId, String paramKey, String state) {
        LambdaQueryWrapper<ParamConfig> wrapper = new LambdaQueryWrapper<ParamConfig>()
                .eq(ParamConfig::getScopeType, scopeType)
                .eq(ParamConfig::getParamKey, paramKey)
                .eq(ParamConfig::getState, state);
        if (kbId == null) {
            wrapper.isNull(ParamConfig::getKbId);
        } else {
            wrapper.eq(ParamConfig::getKbId, kbId);
        }
        return paramConfigMapper.selectOne(wrapper);
    }

    private List<ParamConfig> listConfigs(String scopeType, Long kbId, String paramKey) {
        LambdaQueryWrapper<ParamConfig> wrapper = new LambdaQueryWrapper<ParamConfig>()
                .eq(ParamConfig::getScopeType, scopeType)
                .eq(paramKey != null && !paramKey.isBlank(), ParamConfig::getParamKey, paramKey);
        if (kbId == null) {
            wrapper.isNull(ParamConfig::getKbId);
        } else {
            wrapper.eq(ParamConfig::getKbId, kbId);
        }
        return paramConfigMapper.selectList(wrapper);
    }

    /**
     * 依据元数据校验参数值（类型 + 取值范围），非法时抛 400。
     */
    private void validateValue(ParamMetadata meta, String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "参数值不能为空: " + meta.getParamKey());
        }
        String type = meta.getParamType();
        if ("INT".equals(type)) {
            int v;
            try {
                v = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                throw new BusinessException(400, "参数值需为整数: " + meta.getParamKey());
            }
            if (meta.getMinValue() != null && v < Integer.parseInt(meta.getMinValue())) {
                throw new BusinessException(400, "参数值低于下限(" + meta.getMinValue() + "): " + meta.getParamKey());
            }
            if (meta.getMaxValue() != null && v > Integer.parseInt(meta.getMaxValue())) {
                throw new BusinessException(400, "参数值高于上限(" + meta.getMaxValue() + "): " + meta.getParamKey());
            }
        } else if ("DOUBLE".equals(type)) {
            double v;
            try {
                v = Double.parseDouble(value.trim());
            } catch (NumberFormatException e) {
                throw new BusinessException(400, "参数值需为数值: " + meta.getParamKey());
            }
            if (meta.getMinValue() != null && v < Double.parseDouble(meta.getMinValue())) {
                throw new BusinessException(400, "参数值低于下限(" + meta.getMinValue() + "): " + meta.getParamKey());
            }
            if (meta.getMaxValue() != null && v > Double.parseDouble(meta.getMaxValue())) {
                throw new BusinessException(400, "参数值高于上限(" + meta.getMaxValue() + "): " + meta.getParamKey());
            }
        } else if ("BOOLEAN".equals(type)) {
            String t = value.trim().toLowerCase();
            if (!"true".equals(t) && !"false".equals(t)) {
                throw new BusinessException(400, "参数值需为布尔值(true/false): " + meta.getParamKey());
            }
        }
        // STRING 类型不校验
    }

    private void writeHistory(String paramKey, String scopeType, Long kbId,
                              String oldValue, String newValue, String action, Long userId) {
        ParamHistory h = new ParamHistory();
        h.setParamKey(paramKey);
        h.setScopeType(scopeType);
        h.setKbId(kbId);
        h.setOldValue(oldValue);
        h.setNewValue(newValue);
        h.setAction(action);
        h.setOperatorId(userId);
        h.setOperatorName(usernameOf(userId));
        h.setOperateTime(LocalDateTime.now());
        paramHistoryMapper.insert(h);
    }

    private String usernameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userMapper.selectById(userId);
        return user == null ? null : user.getUsername();
    }
}