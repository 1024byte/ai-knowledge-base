package com.hai.aiknowledgebase;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.OnnxEmbeddingModel;
import dev.langchain4j.model.embedding.onnx.PoolingMode;
import dev.langchain4j.store.embedding.CosineSimilarity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingSimilarityTest {

    private static EmbeddingModel embeddingModel;

    @BeforeAll
    static void setUp() throws Exception {
        String modelPath = new ClassPathResource("onnx_model/model.onnx").getFile().getAbsolutePath();
        String tokenizerPath = new ClassPathResource("onnx_model/tokenizer.json").getFile().getAbsolutePath();
        embeddingModel = new OnnxEmbeddingModel(modelPath, tokenizerPath, PoolingMode.MEAN);
    }

    @Test
    void testSentenceSimilarity() {
        String s1 = "Most Americans switch careers at least three times.";
        String s2 = "Gone are the days when people retired from the same company.";

        Embedding e1 = embeddingModel.embed(s1).content();
        Embedding e2 = embeddingModel.embed(s2).content();

        double sim = CosineSimilarity.between(e1, e2);

        System.out.println("Test similarity: " + sim);
        System.out.println("Sentence 1: " + s1);
        System.out.println("Sentence 2: " + s2);

        // 语义相关的两句话，余弦相似度应大于 0.2
        assertThat(sim).isGreaterThan(0.2);
    }

    @Test
    void testIdenticalSentenceSimilarity() {
        String s = "Most Americans switch careers at least three times.";

        Embedding e1 = embeddingModel.embed(s).content();
        Embedding e2 = embeddingModel.embed(s).content();

        double sim = CosineSimilarity.between(e1, e2);

        System.out.println("Identical sentence similarity: " + sim);

        // 完全相同的句子，余弦相似度应接近 1.0
        assertThat(sim).isGreaterThan(0.99);
    }

    @Test
    void testUnrelatedSentenceSimilarity() {
        String s1 = "Most Americans switch careers at least three times.";
        String s2 = "The quick brown fox jumps over the lazy dog.";

        Embedding e1 = embeddingModel.embed(s1).content();
        Embedding e2 = embeddingModel.embed(s2).content();

        double sim = CosineSimilarity.between(e1, e2);

        System.out.println("Unrelated sentence similarity: " + sim);
        System.out.println("Sentence 1: " + s1);
        System.out.println("Sentence 2: " + s2);

        // 语义不相关的句子，相似度应低于语义相关的句子
        // 但注意：即使是无关句子，在嵌入空间中也可能有一定相似度
        assertThat(sim).isLessThan(0.9);
    }
}
