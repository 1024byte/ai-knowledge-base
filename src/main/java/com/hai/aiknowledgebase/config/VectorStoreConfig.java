package com.hai.aiknowledgebase.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Slf4j
@Configuration
public class VectorStoreConfig {

    @Value("${vectorstore.pgvector.table-name:embeddings}")
    private String tableName;

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(EmbeddingModel embeddingModel, DataSource dataSource) {
        log.info("初始化 PGVector 向量存储，表名: {}", tableName);
        int dimension = embeddingModel.dimension();
        log.info("嵌入向量维度: {}", dimension);

        // 使用 datasourceBuilder()，然后传入 DataSource
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)    // ✅ 正确的方法名
                .table(tableName)      // 注意：这里是 tableName，不是 table
                .dimension(dimension)
                .createTable(true)
                .dropTableFirst(false)
                .build();
    }
    @Bean
    public EmbeddingModel embeddingModel() {
        // BGE 中文模型，默认使用 CPU，适合生产环境
        return new BgeSmallZhEmbeddingModel();
    }
}