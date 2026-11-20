package com.hai.aiknowledgebase.task;

import com.hai.aiknowledgebase.service.KnowledgeDocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <h2>文档向量化状态兜底聚合任务</h2>
 *
 * <p>定时扫描处于「向量化中」但片段已全部进入终态（已完成/失败）的文档，
 * 重新按片段状态聚合文档状态，修复因进程中断或异常导致的文档状态漂移。</p>
 *
 * <p>聚合规则由 {@code KnowledgeDocumentService#recomputeDocumentVectorStatus} 统一维护，
 * 本任务仅负责触发扫描，保证文档状态最终一致。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentVectorStatusReconcileTask {

    private final KnowledgeDocumentService knowledgeDocumentService;

    /**
     * 每 5 分钟执行一次兜底聚合。
     *
     * <p>仅在扫描到处于「向量化中」的文档时才输出日志，避免空转噪声。</p>
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void reconcileStaleVectorStatus() {
        int scanned = knowledgeDocumentService.reconcileStaleVectorStatus();
        if (scanned > 0) {
            log.info("文档向量化状态兜底聚合完成: 扫描 {} 个向量化中文档", scanned);
        }
    }
}