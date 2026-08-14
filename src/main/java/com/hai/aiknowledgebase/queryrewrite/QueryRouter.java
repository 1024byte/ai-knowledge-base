package com.hai.aiknowledgebase.queryrewrite;

import com.hai.aiknowledgebase.dto.CustomChatMessage;
import com.hai.aiknowledgebase.dto.RewriteStrategyEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 按需改写路由分类器
 *
 * <p>根据查询特征判定应执行的改写策略：</p>
 * <ul>
 *   <li>有指代词 + 有对话历史 → RESOLVE_ONLY（纠错 + 指代消解）</li>
 *   <li>默认 → SIMPLE_REWRITE（纠错 + LLM 改写）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRouter {

    /** 指代词正则：检测"它/这个/那个/上面/前面/这里/那里/这些/那些/该/其"等 */
    private static final Pattern PRONOUN_PATTERN = Pattern.compile(
            "它(?:们)?|上面|前面|这里|那里|这些|那些|" +
                    "该(?:文档|文件|系统|项目|功能|模块|方法|类|接口|服务|配置|组件)?|" +
                    "其(?:中)?|这(?:个|些)?|那(?:个|些)?"
    );

    /**
     * 路由策略选择
     *
     * <p>本地 LLM 改写后，路由仅需判断是否需要指代消解，
     * 不再需要规则引擎的复杂路由逻辑。</p>
     */
    public RewriteStrategyEnum route(String query, List<CustomChatMessage> history) {
        if (history != null && !history.isEmpty() && containsPronouns(query)) {
            return RewriteStrategyEnum.RESOLVE_ONLY;
        }
        return RewriteStrategyEnum.SIMPLE_REWRITE;
    }

    /**
     * 检测查询中是否包含中文指代词
     */
    private boolean containsPronouns(String query) {
        return PRONOUN_PATTERN.matcher(query).find();
    }
}