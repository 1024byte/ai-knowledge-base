package com.hai.aiknowledgebase.queryrewrite.word;

public class Candidate {

    public final String key;
    public final String replacement;
    public final boolean isFixedMapping;
    public final double confidence;

    public Candidate(String key, String replacement, boolean isFixedMapping, double confidence) {
        this.key = key;
        this.replacement = replacement;
        this.isFixedMapping = isFixedMapping;
        this.confidence = confidence;
    }

}
