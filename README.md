# AI知识库系统

基于RAG（检索增强生成）技术的个人知识库系统，支持文档上传、向量化存储与智能问答。

## 技术栈

- **后端框架**：Spring Boot 3.2
- **AI框架**：Spring AI 1.0.0-M3
- **LLM**：DeepSeek API
- **向量存储**：SimpleVectorStore（可替换为Chroma、Pinecone等）
- **文档处理**：支持txt、md、pdf格式

## 核心功能

1. **文档上传**：支持txt、md、pdf格式文档上传
2. **文档分割**：自动将长文档分割成适合检索的文本块
3. **向量化存储**：使用Embedding模型将文本转换为向量并存储
4. **语义检索**：基于向量相似度检索相关文档片段
5. **智能问答**：结合检索结果和LLM生成回答

## 快速开始

### 1. 环境要求

- JDK 17+
- Maven 3.6+
- DeepSeek API Key

### 2. 配置API Key

在`application.yml`中配置DeepSeek API Key：

```yaml
spring:
  ai:
    openai:
      api-key: your-deepseek-api-key
      base-url: https://api.deepseek.com
```

或通过环境变量：

```bash
export DEEPSEEK_API_KEY=your-deepseek-api-key
```

### 3. 运行项目

```bash
mvn spring-boot:run
```

### 4. 使用API

#### 上传文档

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@your-document.txt"
```

#### 智能问答

```bash
curl -X POST http://localhost:8080/api/chat/ask \
  -H "Content-Type: application/json" \
  -d '{"question": "你的问题", "topK": 3}'
```

#### 语义检索

```bash
curl -X GET "http://localhost:8080/api/chat/search?query=你的查询&topK=5"
```

## API接口

### 文档管理

- `POST /api/documents/upload` - 上传文档
  - 参数：file (MultipartFile)
  - 返回：DocumentUploadResponse

### 智能问答

- `POST /api/chat/ask` - 问答接口
  - 参数：ChatRequest (question, topK)
  - 返回：ChatResponse (answer, sources, processingTimeMs)

- `GET /api/chat/search` - 语义检索
  - 参数：query, topK
  - 返回：List<SearchResult>

## 项目结构

```
ai-knowledge-base/
├── src/main/java/com/example/aiknowledgebase/
│   ├── AiKnowledgeBaseApplication.java    # 主启动类
│   ├── config/                            # 配置类
│   │   └── VectorStoreConfig.java
│   ├── controller/                        # 控制器
│   │   ├── ChatController.java
│   │   └── DocumentController.java
│   ├── dto/                               # 数据传输对象
│   │   ├── ChatRequest.java
│   │   ├── ChatResponse.java
│   │   ├── DocumentUploadResponse.java
│   │   └── SearchResult.java
│   └── service/                           # 服务层
│       ├── ChatService.java
│       └── DocumentService.java
├── src/main/resources/
│   └── application.yml                    # 配置文件
└── pom.xml                                # Maven配置
```

## 配置说明

### application.yml

```yaml
server:
  port: 8080                          # 服务端口

spring:
  ai:
    openai:
      api-key: ${DEEPSEEK_API_KEY}    # DeepSeek API Key
      base-url: https://api.deepseek.com
      chat:
        options:
          model: deepseek-chat        # 对话模型
          temperature: 0.7            # 温度参数
      embedding:
        options:
          model: deepseek-embedding   # Embedding模型

document:
  upload-path: ./data/uploads         # 文档上传路径
  chunk-size: 500                     # 文本块大小
  chunk-overlap: 50                   # 文本块重叠大小

vectorstore:
  simple:
    file-path: ./data/vector-store.json  # 向量存储文件路径
```

## 后续优化方向

1. **向量数据库**：替换SimpleVectorStore为Chroma、Pinecone等专业向量数据库
2. **文档格式**：支持更多文档格式（Word、Excel等）
3. **前端界面**：开发Web界面，提供更好的用户体验
4. **对话历史**：支持多轮对话，保持上下文
5. **流式输出**：支持流式输出，提升用户体验
6. **性能优化**：异步处理、缓存、批量处理等

## 注意事项

1. **API Key安全**：不要将API Key提交到Git仓库
2. **文档大小**：注意控制单个文档大小，避免内存溢出
3. **向量存储**：SimpleVectorStore适合开发测试，生产环境建议使用专业向量数据库
4. **成本控制**：注意API调用成本，合理设置topK参数

## License

MIT