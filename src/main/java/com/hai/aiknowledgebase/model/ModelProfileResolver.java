package com.hai.aiknowledgebase.model;

import com.hai.aiknowledgebase.entity.ModelConfig;
import com.hai.aiknowledgebase.service.ParamValueProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型生效配置解析器。
 *
 * <p>解析优先级：</p>
 * <ol>
 *   <li>评测参数快照中的 {@code model.chat.id / model.rewrite.id / model.rerank.id}（运行期间冻结）</li>
 *   <li>{@code model_config} 中该业务类型的生效配置（{@code is_active=true}）</li>
 *   <li>均未命中返回 null，由调用方回落 application.yml 的 bootstrap 配置</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelProfileResolver {

    private final ModelConfigService modelConfigService;
    private final ParamValueProvider paramValueProvider;

    /** CHAT 配置解析不到 Key 时的 bootstrap 兜底（沿用 yml deepseek.api-key） */
    @Value("${deepseek.api-key:}")
    private String bootstrapChatApiKey;

    /**
     * 解析指定业务类型的运行时配置。
     *
     * @return 配置快照；数据库无配置时返回 null
     */
    public ModelProfile resolve(ModelBizType type) {
        ModelConfig config = resolveEntity(type);
        if (config == null) {
            return null;
        }
        String fallbackKey = type == ModelBizType.CHAT ? bootstrapChatApiKey : null;
        return modelConfigService.toProfile(config, fallbackKey);
    }

    private ModelConfig resolveEntity(ModelBizType type) {
        String overrideId = paramValueProvider.getString(type.overrideKey(), null);
        if (overrideId != null && !overrideId.isBlank()) {
            try {
                ModelConfig frozen = modelConfigService.getById(Long.parseLong(overrideId.trim()));
                if (frozen != null) {
                    return frozen;
                }
                log.warn("参数快照中的模型配置不存在: type={}, id={}，回退生效配置", type, overrideId);
            } catch (NumberFormatException e) {
                log.warn("参数快照中的模型配置 ID 非法: type={}, id={}", type, overrideId);
            }
        }
        return modelConfigService.getActive(type);
    }
}
