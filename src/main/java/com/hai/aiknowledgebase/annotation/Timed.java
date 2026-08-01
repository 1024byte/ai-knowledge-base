package com.hai.aiknowledgebase.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 方法耗时统计注解
 *
 * <p>标注在需要记录执行耗时的方法上，由 {@code TimingAspect} 自动拦截并打印日志。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * @Timed("查询改写")
 * public QueryRewriteResult rewrite(RewriteRequest request) { ... }
 * }</pre>
 *
 * <h3>日志输出格式</h3>
 * <pre>{@code
 * [Timed] 查询改写 | 耗时: 215ms
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Timed {

    /** 管线节点名称，用于日志标识 */
    String value() default "";
}