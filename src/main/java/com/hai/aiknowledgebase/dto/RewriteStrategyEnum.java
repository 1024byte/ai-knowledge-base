package com.hai.aiknowledgebase.dto;

/**
 * 按需改写策略枚举
 *
 * <p>替代原有的 {@link RoutingDecision}，提供更细粒度的改写路由控制。
 * 由 {@link com.hai.aiknowledgebase.queryrewrite.QueryRouter} 根据查询特征自动选择。</p>
 *
 * <h3>策略说明</h3>
 * <table>
 *   <tr><th>策略</th><th>触发条件</th><th>执行动作</th><th>LLM调用</th></tr>
 *   <tr><td>DIRECT</td><td>简单查询、无指代、目标明确</td><td>直接检索，不改写</td><td>无</td></tr>
 *   <tr><td>CORRECT_ONLY</td><td>简单查询但可能有错别字</td><td>仅纠错，不改写</td><td>无</td></tr>
 *   <tr><td>RESOLVE_ONLY</td><td>有指代词，有对话历史</td><td>纠错 + 指代消解</td><td>1次（消解）</td></tr>
 *   <tr><td>SIMPLE_REWRITE</td><td>中等复杂度，需要同义词扩展</td><td>纠错 + 消解 + L1/L2改写</td><td>1次（消解）</td></tr>
 *   <tr><td>DECOMPOSE</td><td>复合问题、多问句、对比类</td><td>纠错 + 消解 + 问题分解</td><td>2次（消解+分解）</td></tr>
 *   <tr><td>HYDE</td><td>检索质量不达标时兜底</td><td>生成假设性答案再检索</td><td>1次（HyDE）</td></tr>
 * </table>
 *
 * @see com.hai.aiknowledgebase.queryrewrite.QueryRouter 路由分类器
 * @see RoutingDecision 旧版路由决策（已废弃，保留兼容）
 */
public enum RewriteStrategyEnum {

    /** 直接检索：不纠错、不消解、不改写，原始查询直达检索 */
    DIRECT,

    /** 仅纠错：只做三层漏斗纠错，不消解、不改写 */
    CORRECT_ONLY,

    /** 纠错+消解：纠正错别字 + 指代消解，然后直接检索 */
    RESOLVE_ONLY,

    /** 简单改写：纠错 + 消解 + L1规则 + L2 NLP增强 */
    SIMPLE_REWRITE,

    /** 复合分解：纠错 + 消解 + 问题分解为多个子查询 */
    DECOMPOSE,

    /** HyDE兜底：检索质量不达标时，LLM生成假设性答案再检索 */
    HYDE
}