package com.hai.aiknowledgebase.aop;

import com.hai.aiknowledgebase.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 方法耗时统计 AOP 切面
 *
 * <p>拦截所有标注了 {@link Timed} 注解的方法，自动记录执行耗时。</p>
 */
@Slf4j
@Aspect
@Component
public class TimingAspect {

    @Around("@annotation(timed)")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint, Timed timed) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            return joinPoint.proceed();
        } finally {
            long elapsed = System.currentTimeMillis() - start;
            String label = timed.value().isEmpty()
                    ? joinPoint.getSignature().toShortString()
                    : timed.value();
            log.info("[Timed] {} | 耗时: {}ms", label, elapsed);
        }
    }
}