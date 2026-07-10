package com.hai.aiknowledgebase.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.TokenCountEstimator;

public class BgeTokenizerEstimator implements TokenCountEstimator {
    @Override
    public int estimateTokenCountInText(String s) {
        return 0;
    }

    @Override
    public int estimateTokenCountInMessage(ChatMessage chatMessage) {
        return 0;
    }

    @Override
    public int estimateTokenCountInMessages(Iterable<ChatMessage> iterable) {
        return 0;
    }
}
