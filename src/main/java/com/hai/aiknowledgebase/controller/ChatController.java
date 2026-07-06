package com.hai.aiknowledgebase.controller;

import com.hai.aiknowledgebase.dto.ChatRequest;
import com.hai.aiknowledgebase.dto.HaiChatResponse;
import com.hai.aiknowledgebase.dto.SearchResult;
import com.hai.aiknowledgebase.service.ChatService;
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
}