package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.annotation.Timed;
import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.ChatMessageDTO;
import com.hai.aiknowledgebase.dto.ChatRequest;
import com.hai.aiknowledgebase.dto.ChatSessionDTO;
import com.hai.aiknowledgebase.dto.HaiChatResponse;
import com.hai.aiknowledgebase.dto.SearchResult;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.security.SecurityUtils;
import com.hai.aiknowledgebase.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    /**
     * 问答接口（多轮对话）
     */
    @Timed("请求 → 响应 总耗时")
    @PostMapping("/ask")
    public Result<HaiChatResponse> ask(@RequestBody ChatRequest request) {
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            throw new BusinessException(400, "问题内容不能为空");
        }
        if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
            throw new BusinessException(400, "会话ID不能为空");
        }

        Long userId = SecurityUtils.getCurrentUserId();
        log.info("收到问答请求: sessionId={}, userId={}, question={}", request.getSessionId(), userId, request.getQuestion());

        HaiChatResponse response = chatService.chat(
                request.getSessionId(),
                request.getQuestion(),
                userId
        );

        return Result.success(response);
    }

    /**
     * SSE 流式问答接口
     *
     * <p>与 {@link #ask(ChatRequest)} 共享相同的 RAG 前置流程，
     * 但 LLM 生成阶段通过 Server-Sent Events 逐 token 实时推送，
     * 用户无需等待完整答案即可看到首 token 内容。</p>
     *
     * <h3>SSE 事件</h3>
     * <ul>
     *   <li><b>start</b>：回答开始（含 sessionId）</li>
     *   <li><b>token</b>：逐 token 的 AI 回答内容</li>
     *   <li><b>done</b>：回答完成</li>
     *   <li><b>error</b>：异常信息（参数校验错误或服务器错误）</li>
     * </ul>
     */
    @Timed("请求 → 首token 耗时")
    @PostMapping("/ask/stream")
    public SseEmitter askStream(@RequestBody ChatRequest request) {
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            SseEmitter errorEmitter = new SseEmitter();
            try {
                errorEmitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"error\":\"问题内容不能为空\"}"));
            } catch (java.io.IOException e) {
                // 忽略
            }
            errorEmitter.complete();
            return errorEmitter;
        }
        if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
            SseEmitter errorEmitter = new SseEmitter();
            try {
                errorEmitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"error\":\"会话ID不能为空\"}"));
            } catch (java.io.IOException e) {
                // 忽略
            }
            errorEmitter.complete();
            return errorEmitter;
        }

        Long userId = SecurityUtils.getCurrentUserId();
        String sessionId = request.getSessionId();
        log.info("收到流式问答请求: sessionId={}, userId={}, question={}", sessionId, userId, request.getQuestion());

        return chatService.chatStream(sessionId, request.getQuestion(), userId);
    }

    /**
     * 向量检索接口（不生成回答，仅返回相关文档片段）
     */
    @GetMapping("/search")
    public Result<List<SearchResult>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {

        if (query == null || query.trim().isEmpty()) {
            throw new BusinessException(400, "查询内容不能为空");
        }

        log.info("收到检索请求: query={}, topK={}", query, topK);

        List<SearchResult> results = chatService.search(query, topK);
        return Result.success(results);
    }

    /**
     * 获取某个会话的历史消息（用于刷新恢复）
     */
    @GetMapping("/history/{sessionId}")
    public Result<List<ChatMessageDTO>> getHistory(@PathVariable String sessionId) {
        log.info("获取会话历史: {}", sessionId);

        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new BusinessException(400, "会话ID不能为空");
        }

        List<ChatMessageDTO> history = chatService.getHistory(sessionId);
        return Result.success(history);
    }

    /**
     * 获取当前用户的所有会话列表（用于侧边栏）
     */
    @GetMapping("/sessions")
    public Result<List<ChatSessionDTO>> getSessions() {
        Long userId = SecurityUtils.getCurrentUserId();
        log.info("获取会话列表, userId={}", userId);
        List<ChatSessionDTO> sessions = chatService.getSessions(userId != null ? userId.toString() : null);
        return Result.success(sessions);
    }

    /**
     * 删除某个会话
     */
    @DeleteMapping("/session/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        log.info("删除会话: {}", sessionId);

        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new BusinessException(400, "会话ID不能为空");
        }

        chatService.deleteSession(sessionId);
        return Result.success();
    }

    @DeleteMapping("/sessions")
    public Result<Void> deleteAllSessions(@RequestParam(required = false) String userId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        String targetUserId = userId;
        if (targetUserId == null && currentUserId != null) {
            targetUserId = currentUserId.toString();
        }
        log.info("删除历史会话, userId: {}", targetUserId != null ? targetUserId : "全部");
        chatService.deleteAllSessions(targetUserId);
        return Result.success();
    }
}