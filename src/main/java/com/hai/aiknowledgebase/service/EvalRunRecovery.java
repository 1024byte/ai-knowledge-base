package com.hai.aiknowledgebase.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hai.aiknowledgebase.entity.EvalRun;
import com.hai.aiknowledgebase.mapper.EvalRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** 服务重启后清理无法继续执行的残留运行。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EvalRunRecovery implements ApplicationRunner {

    private final EvalRunMapper runMapper;

    @Override
    public void run(ApplicationArguments args) {
        int recovered = runMapper.update(null, new LambdaUpdateWrapper<EvalRun>()
                .in(EvalRun::getState, "PENDING", "RUNNING")
                .set(EvalRun::getState, "FAILED")
                .set(EvalRun::getErrorMessage, "服务重启，运行中断")
                .set(EvalRun::getFinishedAt, LocalDateTime.now()));
        if (recovered > 0) {
            log.warn("已将 {} 个残留评测运行标记为 FAILED（服务重启，运行中断）", recovered);
        }
    }
}
