package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.dto.*;
import com.hai.aiknowledgebase.service.ChatService;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping("/ask")
    public ResponseEntity<HaiChatResponse> ask(@RequestBody ChatRequest request) {
        try {
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }
            
            HaiChatResponse response = chatService.chat(request.getSessionId(),
                request.getQuestion(), 
                request.getTopK()
            );
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("问答失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/search")
    public ResponseEntity<List<SearchResult>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        try {
            List<SearchResult> results = chatService.search(query, topK);
            return ResponseEntity.ok(results);
        } catch (Exception e) {
            log.error("检索失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 获取某个会话的历史消息（用于刷新恢复）
     */
    @GetMapping("/history/{sessionId}")
    public List<ChatMessageDTO> getHistory(@PathVariable String sessionId) {
        log.info("获取会话历史: {}", sessionId);
        return chatService.getHistory(sessionId);
    }

    /**
     * 获取当前用户的所有会话列表（用于侧边栏）
     */
    @GetMapping("/sessions")
    public List<ChatSessionDTO> getSessions() {
        log.info("获取会话列表");
        // 如果接了用户系统，从 @AuthenticationPrincipal 或 SecurityContext 取 userId
        // 这里先设为 null（匿名模式）
        return chatService.getSessions(null);
    }


    /**
     * 删除某个会话（可选）
     */
    @DeleteMapping("/session/{sessionId}")
    public void deleteSession(@PathVariable String sessionId) {
        log.info("删除会话: {}", sessionId);
        chatService.deleteSession(sessionId);
    }
}