package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.common.Result;
import com.hai.aiknowledgebase.dto.ChatMessageDTO;
import com.hai.aiknowledgebase.dto.ChatRequest;
import com.hai.aiknowledgebase.dto.ChatSessionDTO;
import com.hai.aiknowledgebase.dto.HaiChatResponse;
import com.hai.aiknowledgebase.dto.SearchResult;
import com.hai.aiknowledgebase.exception.BusinessException;
import com.hai.aiknowledgebase.service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

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
    @PostMapping("/ask")
    public Result<HaiChatResponse> ask(@RequestBody ChatRequest request) {
        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            throw new BusinessException(400, "问题内容不能为空");
        }
        if (request.getSessionId() == null || request.getSessionId().trim().isEmpty()) {
            throw new BusinessException(400, "会话ID不能为空");
        }

        log.info("收到问答请求: sessionId={}, question={}", request.getSessionId(), request.getQuestion());

        HaiChatResponse response = chatService.chat(
                request.getSessionId(),
                request.getQuestion(),
                request.getTopK() > 0 ? request.getTopK() : 5
        );

        return Result.success(response);
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
        log.info("获取会话列表");
        // 如果接了用户系统，从 @AuthenticationPrincipal 或 SecurityContext 取 userId
        // 这里先设为 null（匿名模式）
        List<ChatSessionDTO> sessions = chatService.getSessions(null);
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
}