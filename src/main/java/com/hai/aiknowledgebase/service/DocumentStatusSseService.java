package com.hai.aiknowledgebase.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <h2>文档状态 SSE 推送服务</h2>
 *
 * <p>通过 Server-Sent Events 向前端实时推送文档处理状态变化。
 * 前端在文件上传后订阅 SSE 通道，无需轮询即可感知异步向量化的完成/失败。</p>
 *
 * <h3>架构说明</h3>
 * <pre>
 * 前端上传文件
 *     │
 *     ├── POST /api/documents/upload  → 返回 docId
 *     │
 *     └── GET /api/documents/sse?docId={id}  → 建立 SSE 长连接
 *              │
 *              │  (异步向量化完成后)
 *              │
 *              └── 服务端推送: {"docId": 42, "status": "active", "timestamp": ...}
 * </pre>
 *
 * <h3>使用方式</h3>
 * <ul>
 *   <li>前端上传后拿到 docId → 调用 {@code EventSource("/api/documents/sse?docId=42")}</li>
 *   <li>后端向量化完成后 → 调用 {@link #pushStatus(Long, String, String)}</li>
 *   <li>SSE 连接自动推送状态变更事件给前端</li>
 * </ul>
 *
 * <h3>关键设计</h3>
 * <ul>
 *   <li><b>ConcurrentHashMap</b>：线程安全地管理所有 SSE 连接，支持并发订阅和推送</li>
 *   <li><b>onCompletion/onTimeout 回调</b>：连接关闭时自动清理，防止内存泄漏</li>
 *   <li><b>30 分钟超时</b>：SSE 连接最长存活 30 分钟，避免僵尸连接</li>
 *   <li><b>推送后自动关闭</b>：状态推送完成后立即关闭连接，避免前端保持不必要的长连接</li>
 * </ul>
 */
@Slf4j
@Service
public class DocumentStatusSseService {

    /** SSE 连接注册表：docId → SseEmitter */
    private final Map<Long, SseEmitter> emitters = new ConcurrentHashMap<>();

    /** SSE 连接超时时间（毫秒），默认 30 分钟 */
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;

    /**
     * <h3>创建 SSE 连接并订阅指定文档的状态变更</h3>
     *
     * <p>前端调用此方法建立 SSE 长连接。连接建立后，前端会收到初始心跳事件，
     * 然后等待异步向量化完成后的状态推送。</p>
     *
     * <h4>SSE 事件格式</h4>
     * <pre>{@code
     * event: connected
     * data: {"docId":42,"status":"processing","message":"已连接到状态推送通道"}
     *
     * event: status
     * data: {"docId":42,"status":"active","message":"文档处理完成","chunkCount":15}
     * }</pre>
     *
     * @param docId 文档 ID
     * @return SseEmitter 实例，由 Spring MVC 管理其生命周期
     */
    public SseEmitter subscribe(Long docId) {
        // 如果已有旧连接，先关闭（避免同一文档有多个订阅）
        SseEmitter existing = emitters.remove(docId);
        if (existing != null) {
            try {
                existing.complete();
            } catch (Exception ignored) {
                // 忽略关闭旧连接时的异常
            }
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitters.put(docId, emitter);

        // 连接关闭时的清理回调（无论正常完成、超时还是异常）
        emitter.onCompletion(() -> {
            emitters.remove(docId);
            log.debug("SSE 连接正常关闭: docId={}", docId);
        });
        emitter.onTimeout(() -> {
            emitters.remove(docId);
            log.debug("SSE 连接超时关闭: docId={}", docId);
        });
        emitter.onError(ex -> {
            emitters.remove(docId);
            log.debug("SSE 连接异常关闭: docId={}, error={}", docId, ex.getMessage());
        });

        // 发送初始连接确认事件
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of(
                            "docId", docId,
                            "status", "processing",
                            "message", "已连接到状态推送通道"
                    )));
        } catch (IOException e) {
            emitters.remove(docId);
            log.warn("SSE 初始连接发送失败: docId={}", docId);
        }

        log.info("SSE 连接已建立: docId={}", docId);
        return emitter;
    }

    /**
     * <h3>推送文档状态变更</h3>
     *
     * <p>由异步向量化线程在完成处理后调用，向前端推送最终状态。</p>
     *
     * <h4>推送内容</h4>
     * <ul>
     *   <li>成功时：status = "active"，包含 chunkCount</li>
     *   <li>失败时：status = "failed"，包含 errorMessage</li>
     * </ul>
     *
     * @param docId        文档 ID
     * @param status       最终状态：active 或 failed
     * @param errorMessage 错误信息（成功时为 null）
     * @param chunkCount   切片数量（失败时为 0）
     */
    public void pushStatus(Long docId, String status, String errorMessage, int chunkCount) {
        SseEmitter emitter = emitters.get(docId);
        if (emitter == null) {
            log.debug("无 SSE 订阅者，跳过推送: docId={}, status={}", docId, status);
            return;
        }

        try {
            Map<String, Object> data = Map.of(
                    "docId", docId,
                    "status", status,
                    "message", "active".equals(status) ? "文档处理完成" : "文档处理失败",
                    "errorMessage", errorMessage != null ? errorMessage : "",
                    "chunkCount", chunkCount
            );
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data(data));
            emitter.complete();
            log.info("SSE 状态推送成功: docId={}, status={}", docId, status);
        } catch (IOException e) {
            emitters.remove(docId);
            log.warn("SSE 状态推送失败: docId={}, error={}", docId, e.getMessage());
        }
    }
}