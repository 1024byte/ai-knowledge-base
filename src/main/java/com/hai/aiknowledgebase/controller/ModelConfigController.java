package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.ModelConfigRequest;
import com.hai.aiknowledgebase.dto.ModelConfigVO;
import com.hai.aiknowledgebase.entity.ModelConfig;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.model.ModelBizType;
import com.hai.aiknowledgebase.model.ModelConfigService;
import com.hai.aiknowledgebase.model.ModelConnectivityTester;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <h2>模型配置接口</h2>
 *
 * <p>统一管理 Chat / 查询改写 / Rerank 三类模型，支持热切换（保存后下一次请求生效）。
 * API Key 只写不读，接口返回中仅包含掩码来源信息。</p>
 *
 * <h3>接口列表</h3>
 * <table>
 *   <tr><th>方法</th><th>路径</th><th>说明</th></tr>
 *   <tr><td>GET</td><td>/api/model-config?bizType=</td><td>配置列表</td></tr>
 *   <tr><td>GET</td><td>/api/model-config/active</td><td>三类当前生效配置</td></tr>
 *   <tr><td>POST</td><td>/api/model-config</td><td>新增</td></tr>
 *   <tr><td>PUT</td><td>/api/model-config/{id}</td><td>修改（全量字段）</td></tr>
 *   <tr><td>DELETE</td><td>/api/model-config/{id}</td><td>删除（生效中的不可删）</td></tr>
 *   <tr><td>POST</td><td>/api/model-config/{id}/activate</td><td>激活（同类型互斥）</td></tr>
 *   <tr><td>POST</td><td>/api/model-config/{id}/test</td><td>连通性测试</td></tr>
 *   <tr><td>POST</td><td>/api/model-config/reload</td><td>手动重载快照</td></tr>
 * </table>
 */
@Slf4j
@RestController
@RequestMapping("/api/model-config")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService modelConfigService;
    private final ModelConnectivityTester connectivityTester;

    @GetMapping
    public Result<List<ModelConfigVO>> list(@RequestParam(required = false) String bizType) {
        List<ModelConfig> configs = bizType == null || bizType.isBlank()
                ? modelConfigService.listAll()
                : modelConfigService.list(requireType(bizType));
        return Result.success(configs.stream().map(this::toVO).collect(Collectors.toList()));
    }

    @GetMapping("/active")
    public Result<Map<String, ModelConfigVO>> active() {
        Map<String, ModelConfigVO> result = new LinkedHashMap<>();
        for (ModelBizType type : ModelBizType.values()) {
            ModelConfig config = modelConfigService.getActive(type);
            if (config != null) {
                result.put(type.code(), toVO(config));
            }
        }
        return Result.success(result);
    }

    @PostMapping
    public Result<Long> create(@RequestBody ModelConfigRequest request) {
        ModelConfig config = new ModelConfig();
        fill(config, request);
        if (config.getSortOrder() == null) {
            config.setSortOrder(0);
        }
        config.setIsActive(Boolean.TRUE.equals(request.getActive()));
        Long id = modelConfigService.create(config, request.getApiKey());
        log.info("新增模型配置: id={}, bizType={}, name={}", id, config.getBizType(), config.getName());
        return Result.success(id);
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @RequestBody ModelConfigRequest request) {
        ModelConfig config = new ModelConfig();
        fill(config, request);
        modelConfigService.update(id, config, request.getApiKey(), Boolean.TRUE.equals(request.getClearApiKey()));
        log.info("修改模型配置: id={}", id);
        return Result.success();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        modelConfigService.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/activate")
    public Result<Void> activate(@PathVariable Long id) {
        modelConfigService.activate(id);
        return Result.success();
    }

    @PostMapping("/{id}/test")
    public Result<Map<String, Object>> test(@PathVariable Long id) {
        return Result.success(connectivityTester.test(id));
    }

    @PostMapping("/reload")
    public Result<Void> reload() {
        modelConfigService.reload();
        return Result.success();
    }

    // ==================== 转换 ====================

    private void fill(ModelConfig target, ModelConfigRequest request) {
        target.setBizType(request.getBizType() == null ? null : request.getBizType().trim().toUpperCase());
        target.setName(request.getName());
        target.setProvider(request.getProvider() == null ? null : request.getProvider().trim().toUpperCase());
        target.setBaseUrl(request.getBaseUrl());
        target.setModelName(request.getModelName());
        target.setApiKeyRef(request.getApiKeyRef());
        target.setTemperature(request.getTemperature());
        target.setMaxTokens(request.getMaxTokens());
        target.setTimeoutMs(request.getTimeoutMs());
        target.setExtraJson(request.getExtraJson());
        if (request.getSortOrder() != null) {
            target.setSortOrder(request.getSortOrder());
        }
    }

    private ModelConfigVO toVO(ModelConfig config) {
        boolean hasKey = notBlank(config.getApiKeyRef()) || notBlank(config.getApiKeyCipher());
        String mode = "NONE";
        String ref = null;
        if (notBlank(config.getApiKeyRef())) {
            mode = "ENV";
            ref = stripEnvPrefix(config.getApiKeyRef());
        } else if (notBlank(config.getApiKeyCipher())) {
            mode = "ENCRYPTED";
        }
        return ModelConfigVO.builder()
                .id(config.getId())
                .bizType(config.getBizType())
                .name(config.getName())
                .provider(config.getProvider())
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .hasApiKey(hasKey)
                .apiKeyMode(mode)
                .apiKeyRef(ref)
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeoutMs(config.getTimeoutMs())
                .extraJson(config.getExtraJson())
                .active(Boolean.TRUE.equals(config.getIsActive()))
                .sortOrder(config.getSortOrder())
                .createdAt(config.getCreatedAt())
                .updatedAt(config.getUpdatedAt())
                .build();
    }

    private ModelBizType requireType(String bizType) {
        ModelBizType type = ModelBizType.of(bizType);
        if (type == null) {
            throw new BusinessException(400, "bizType 非法，应为 CHAT / REWRITE / RERANK");
        }
        return type;
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    /** 与 SecretResolver 保持一致：仅剥离小写 env: 前缀。 */
    private static String stripEnvPrefix(String ref) {
        String value = ref.trim();
        return value.startsWith("env:") ? value.substring(4).trim() : value;
    }
}
