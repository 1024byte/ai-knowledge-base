package com.hai.aiknowledgebase.model;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hai.aiknowledgebase.entity.ModelConfig;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.mapper.ModelConfigMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * <h2>模型配置服务</h2>
 *
 * <p>负责 {@code model_config} 的 CRUD 与内存快照维护。快照在以下时机刷新：
 * 启动、每次写操作、以及固定间隔（默认 30s，见 {@code model.config.reload-ms}）。</p>
 *
 * <p>写操作同时刷新，保证「保存 → 下一次请求即生效」；定时刷新兜底多实例/直连改库场景。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    private final ModelConfigMapper modelConfigMapper;
    private final SecretResolver secretResolver;

    @Value("${model.config.reload-ms:30000}")
    private long reloadIntervalMs;

    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile boolean loadFailureLogged = false;
    private ScheduledExecutorService reloadScheduler;

    /** 内存快照：按 ID 索引 + 按业务类型索引的生效配置。 */
    private record Snapshot(Map<Long, ModelConfig> byId, Map<ModelBizType, ModelConfig> active) {
        static Snapshot empty() {
            return new Snapshot(Collections.emptyMap(), Collections.emptyMap());
        }
    }

    // ==================== 生命周期 ====================

    @PostConstruct
    public void init() {
        reload();
        reloadScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "model-config-reload");
            t.setDaemon(true);
            return t;
        });
        reloadScheduler.scheduleWithFixedDelay(this::reload,
                reloadIntervalMs, reloadIntervalMs, TimeUnit.MILLISECONDS);
        log.info("模型配置中心已启动，刷新间隔 {}ms", reloadIntervalMs);
    }

    @PreDestroy
    public void destroy() {
        if (reloadScheduler != null) {
            reloadScheduler.shutdownNow();
        }
    }

    /** 重新加载配置快照。数据库不可用时保留上次快照。 */
    public void reload() {
        List<ModelConfig> all;
        try {
            all = modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                    .orderByAsc(ModelConfig::getSortOrder)
                    .orderByAsc(ModelConfig::getId));
        } catch (Exception e) {
            if (!loadFailureLogged) {
                log.warn("模型配置加载失败（可能未执行 sql/model_config.sql），将回落 yml 配置: {}", e.getMessage());
                loadFailureLogged = true;
            } else {
                log.debug("模型配置加载仍失败: {}", e.getMessage());
            }
            return;
        }
        loadFailureLogged = false;
        Map<Long, ModelConfig> byId = new HashMap<>();
        Map<ModelBizType, ModelConfig> active = new EnumMap<>(ModelBizType.class);
        for (ModelConfig cfg : all) {
            byId.put(cfg.getId(), cfg);
            if (Boolean.TRUE.equals(cfg.getIsActive())) {
                ModelBizType type = ModelBizType.of(cfg.getBizType());
                if (type != null) {
                    active.put(type, cfg);
                }
            }
        }
        snapshot = new Snapshot(Collections.unmodifiableMap(byId), Collections.unmodifiableMap(active));
        log.info("模型配置快照已刷新: 共 {} 条，生效 {} 条", all.size(), active.size());
    }

    // ==================== 查询 ====================

    public List<ModelConfig> listAll() {
        return modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                .orderByAsc(ModelConfig::getBizType)
                .orderByAsc(ModelConfig::getSortOrder)
                .orderByAsc(ModelConfig::getId));
    }

    public List<ModelConfig> list(ModelBizType type) {
        return modelConfigMapper.selectList(new LambdaQueryWrapper<ModelConfig>()
                .eq(ModelConfig::getBizType, type.code())
                .orderByAsc(ModelConfig::getSortOrder)
                .orderByAsc(ModelConfig::getId));
    }

    public ModelConfig getById(Long id) {
        ModelConfig cached = snapshot.byId().get(id);
        // 快照未命中时回查数据库，兼容「刚插入尚未刷新快照」与多实例直连改库场景
        return cached != null ? cached : modelConfigMapper.selectById(id);
    }

    public ModelConfig getActive(ModelBizType type) {
        return snapshot.active().get(type);
    }

    /** 当前生效配置的 ID 快照（键为小写业务类型），供评测参数冻结使用。 */
    public Map<String, String> activeIdSnapshot() {
        Map<String, String> ids = new HashMap<>();
        snapshot.active().forEach((type, cfg) -> ids.put(type.name().toLowerCase(), String.valueOf(cfg.getId())));
        return ids;
    }

    // ==================== 写操作 ====================

    @Transactional
    public Long create(ModelConfig config, String rawApiKey) {
        validate(config);
        if (Boolean.TRUE.equals(config.getIsActive())) {
            config.setIsActive(false);
            applyApiKey(config, rawApiKey, false);
            modelConfigMapper.insert(config);
            activate(config.getId());
        } else {
            applyApiKey(config, rawApiKey, false);
            modelConfigMapper.insert(config);
            reload();
        }
        return config.getId();
    }

    @Transactional
    public void update(Long id, ModelConfig config, String rawApiKey, boolean clearApiKey) {
        if (getById(id) == null) {
            throw new BusinessException(404, "模型配置不存在: " + id);
        }
        config.setId(id);
        validate(config);
        config.setIsActive(null);
        applyApiKey(config, rawApiKey, clearApiKey);
        modelConfigMapper.updateById(config);
        boolean switchToEnvRef = !isBlank(config.getApiKeyRef())
                && (rawApiKey == null || rawApiKey.isBlank());
        if (clearApiKey || switchToEnvRef) {
            // updateById 会忽略 null 字段，清空 Key 需显式置空，避免新旧 Key 来源并存
            LambdaUpdateWrapper<ModelConfig> clear = new LambdaUpdateWrapper<ModelConfig>()
                    .eq(ModelConfig::getId, id)
                    .set(ModelConfig::getApiKeyCipher, null);
            if (clearApiKey) {
                clear.set(ModelConfig::getApiKeyRef, null);
            }
            modelConfigMapper.update(null, clear);
        }
        reload();
    }

    @Transactional
    public void activate(Long id) {
        ModelConfig config = getById(id);
        if (config == null) {
            throw new BusinessException(404, "模型配置不存在: " + id);
        }
        ModelBizType type = ModelBizType.of(config.getBizType());
        if (type == null) {
            throw new BusinessException(400, "模型配置的业务类型非法");
        }
        // 先取消同类型其它配置，再激活目标配置
        modelConfigMapper.update(null, new LambdaUpdateWrapper<ModelConfig>()
                .eq(ModelConfig::getBizType, type.code())
                .eq(ModelConfig::getIsActive, true)
                .set(ModelConfig::getIsActive, false));
        modelConfigMapper.update(null, new LambdaUpdateWrapper<ModelConfig>()
                .eq(ModelConfig::getId, id)
                .set(ModelConfig::getIsActive, true));
        reload();
        log.info("模型配置已激活: id={}, bizType={}, name={}", id, type, config.getName());
    }

    @Transactional
    public void delete(Long id) {
        ModelConfig config = getById(id);
        if (config == null) {
            throw new BusinessException(404, "模型配置不存在: " + id);
        }
        if (Boolean.TRUE.equals(config.getIsActive())) {
            throw new BusinessException(400, "生效中的模型配置不可删除，请先激活其它配置");
        }
        modelConfigMapper.deleteById(id);
        reload();
    }

    // ==================== 配置 → 运行时快照 ====================

    /**
     * 将数据库配置转换为运行时快照。
     *
     * @param config          数据库配置
     * @param fallbackApiKey  解析不到 Key 时使用的 bootstrap 兜底（仅 CHAT 使用）
     */
    public ModelProfile toProfile(ModelConfig config, String fallbackApiKey) {
        if (config == null) {
            return null;
        }
        String apiKey = secretResolver.resolve(config.getApiKeyRef(), config.getApiKeyCipher());
        if ((apiKey == null || apiKey.isBlank()) && fallbackApiKey != null && !fallbackApiKey.isBlank()) {
            apiKey = fallbackApiKey;
        }
        return ModelProfile.builder()
                .id(config.getId())
                .bizType(ModelBizType.of(config.getBizType()))
                .name(config.getName())
                .provider(ModelProviderType.of(config.getProvider()))
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .apiKey(apiKey)
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeoutMs(config.getTimeoutMs())
                .extra(config.getExtraJson())
                .build();
    }

    // ==================== 内部工具 ====================

    private void applyApiKey(ModelConfig config, String rawApiKey, boolean clearApiKey) {
        if (clearApiKey) {
            config.setApiKeyCipher(null);
            config.setApiKeyRef(null);
            return;
        }
        if (rawApiKey == null || rawApiKey.isBlank()) {
            // 不传 Key 表示保持原值：更新场景由 updateById 忽略 null 字段
            return;
        }
        if (!secretResolver.canEncrypt()) {
            throw new BusinessException(400,
                    "未配置 model.secret-key（环境变量 MODEL_CONFIG_SECRET），无法加密保存 API Key；"
                            + "请改用 apiKeyRef 环境变量引用方式");
        }
        // 明文 Key 一律加密存储，不落库明文；同时清空环境变量引用，避免双来源
        config.setApiKeyCipher(secretResolver.encrypt(rawApiKey));
        config.setApiKeyRef(null);
    }

    private void validate(ModelConfig config) {
        ModelBizType type = ModelBizType.of(config.getBizType());
        if (type == null) {
            throw new BusinessException(400, "bizType 非法，应为 CHAT / REWRITE / RERANK");
        }
        ModelProviderType provider = ModelProviderType.of(config.getProvider());
        if (provider == null) {
            throw new BusinessException(400, "provider 非法，应为 OPENAI_COMPAT / OLLAMA / ONNX / DASHSCOPE / AUTO");
        }
        if (config.getName() == null || config.getName().isBlank()) {
            throw new BusinessException(400, "name 不能为空");
        }
        switch (provider) {
            case OPENAI_COMPAT, DASHSCOPE -> {
                if (isBlank(config.getBaseUrl())) {
                    throw new BusinessException(400, provider + " 需要配置 baseUrl");
                }
                if (isBlank(config.getModelName())) {
                    throw new BusinessException(400, provider + " 需要配置 modelName");
                }
            }
            case OLLAMA -> {
                if (isBlank(config.getModelName())) {
                    throw new BusinessException(400, "OLLAMA 需要配置 modelName");
                }
                if (isBlank(config.getBaseUrl())) {
                    config.setBaseUrl("http://localhost:11434");
                }
            }
            case ONNX -> {
                if (isBlank(config.getModelName())) {
                    throw new BusinessException(400, "ONNX 需要配置 modelName");
                }
            }
            case AUTO -> {
                // AUTO 仅用于 Rerank，无需额外必填项
            }
        }
        if (provider == ModelProviderType.ONNX && type != ModelBizType.RERANK) {
            throw new BusinessException(400, "ONNX 仅支持 RERANK 业务类型");
        }
        if (provider == ModelProviderType.DASHSCOPE && type != ModelBizType.RERANK) {
            throw new BusinessException(400, "DASHSCOPE 原生接口仅支持 RERANK；Chat/改写请使用 OPENAI_COMPAT");
        }
        if (type == ModelBizType.REWRITE
                && provider != ModelProviderType.OPENAI_COMPAT
                && provider != ModelProviderType.OLLAMA) {
            throw new BusinessException(400, "REWRITE 仅支持 OPENAI_COMPAT / OLLAMA");
        }
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
