package com.hai.aiknowledgebase.queryrewrite.word;

public class Replacement {

    public  final int start;
    public final int end;
    public final String replacement;

    public Replacement(int start, int end, String replacement) {
        this.start = start;
        this.end = end;
        this.replacement = replacement;
    }
}
