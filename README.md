# AI知识库系统

基于 RAG（检索增强生成）技术的企业级知识库系统，支持多格式文档解析、混合检索、智能问答和多轮对话。

## 技术栈

| 组件 | 技术选型 |
|------|----------|
| 后端框架 | Spring Boot 3.2 + JDK 21 |
| AI 框架 | LangChain4j 1.17.2 |
| LLM | DeepSeek（chat + embedding） |
| 向量数据库 | PostgreSQL + PGVector |
| 关系数据库 | PostgreSQL + MyBatis-Plus 3.5.9 |
| 中文分词 | jieba-analysis |
| 文档解析 | MinerU API（PDF）、Python 脚本（DOCX） |
| 缓存 | Caffeine |

## 核心功能

### 1. 文档管理
- **多格式支持**：PDF、DOCX、Markdown、TXT
- **异步处理**：上传后立即返回，后台异步向量化，SSE 实时推送状态
- **智能去重**：字节级 SHA-256（完全相同）+ 文本级 SHA-256（内容相同格式不同）
- **分类管理**：支持多级分类目录，删除文档时自动清理空目录
- **流式预览**：PDF/图片浏览器内嵌预览，大文件零拷贝流式传输

### 2. 文档处理
- **PDF 解析**：MinerU API 精准解析（支持 OCR、公式、表格）
- **DOCX 解析**：Python 脚本转换为 Markdown
- **智能切片**：基于 Markdown 标题层级的递归切片，保留语义完整性
  - 最小块大小：400 tokens
  - 最大块大小：800 tokens
  - 重叠比例：20%

### 3. 混合检索
- **双路并行召回**：向量语义检索（PGVector） + BM25 关键词检索
- **RRF 融合排序**：Reciprocal Rank Fusion 算法融合两路结果
- **中文分词**：jieba 搜索引擎模式 + 停用词过滤
- **降级策略**：混合检索关闭时自动降级为纯向量检索

### 4. 智能问答
- **RAG 问答**：检索相关文档片段 + LLM 生成回答，附带引用来源
- **多轮对话**：基于 PostgreSQL 持久化会话历史，支持上下文理解
- **会话管理**：创建/删除会话，查看历史消息

### 5. 查询优化
- **查询改写**：同义词扩展 + 固定映射 + 意图识别
- **三级意图路由**：规则匹配（L1）→ LLM 分类（L2）→ 默认兜底（L3）
- **查询纠错**：自动纠正用户输入中的拼写错误

## 快速开始

### 环境要求

- JDK 21+
- Maven 3.8+
- PostgreSQL 15+（需安装 PGVector 扩展）
- Python 3.9+（用于 DOCX 解析脚本）
- MinerU API Token（用于 PDF 解析，可选）

### 1. 初始化数据库

```sql
-- 创建数据库
CREATE DATABASE knowledge_base;

-- 启用 PGVector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- 启动项目后会自动建表，或手动执行以下 SQL：
-- 详见项目中的 entity 类定义
```

### 2. 配置环境

编辑 `src/main/resources/application-dev.yml`：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/knowledge_base
    username: admin
    password: admin

deepseek:
  api-key: your-deepseek-api-key
  base-url: https://api.deepseek.com
  chat-model: deepseek-v4-flash
  embedding-model: deepseek-v4-flash
  temperature: 0.7

# PDF 解析（可选，不配置则使用内置简单解析器）
mineru:
  api:
    url: https://mineru.net/api/v4/extract/task
    token: your-mineru-token
  python:
    path: /path/to/python.exe
```

### 3. 启动项目

```bash
mvn spring-boot:run
```

服务启动后访问 `http://localhost:8080`。

## API 接口

### 文档管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/documents/upload` | 上传文档（`multipart/form-data`，参数 `file`、`category`） |
| `GET` | `/api/documents/list` | 获取所有文档列表（含状态、chunk 数量） |
| `GET` | `/api/documents/{id}/status` | 查询文档处理状态 |
| `GET` | `/api/documents/sse?docId={id}` | SSE 实时订阅文档处理状态 |
| `GET` | `/api/documents/{id}/content` | 流式返回文档内容（浏览器内嵌预览） |
| `DELETE` | `/api/documents/{id}` | 删除文档（级联清理向量/索引/哈希/文件/空目录） |

### 分类管理

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/documents/category` | 创建分类 |
| `DELETE` | `/api/documents/category/{name}` | 删除分类（仅空分类） |
| `GET` | `/api/documents/categories` | 获取所有分类列表 |
| `GET` | `/api/documents/category/{name}` | 获取分类下文件列表 |

### 智能问答

| 方法 | 路径 | 说明 |
|------|------|------|
| `POST` | `/api/chat/ask` | 问答（含多轮对话） |
| `GET` | `/api/chat/search` | 纯向量检索（不生成回答） |
| `GET` | `/api/chat/history/{sessionId}` | 获取会话历史消息 |
| `GET` | `/api/chat/sessions` | 获取所有会话列表 |
| `DELETE` | `/api/chat/session/{sessionId}` | 删除会话 |

### 请求示例

**上传文档：**
```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@章程.pdf" \
  -F "category=规章制度"
```

**SSE 订阅状态：**
```javascript
const eventSource = new EventSource('/api/documents/sse?docId=42');
eventSource.addEventListener('status', (e) => {
    const { status, chunkCount, errorMessage } = JSON.parse(e.data);
    console.log(`文档处理完成: ${status}, 片段数: ${chunkCount}`);
});
```

**智能问答：**
```bash
curl -X POST http://localhost:8080/api/chat/ask \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "user-001",
    "question": "公司的年假政策是什么？",
    "topK": 5
  }'
```

**语义检索：**
```bash
curl "http://localhost:8080/api/chat/search?query=绩效考核&topK=5"
```

## 项目结构

```
ai-knowledge-base/
├── src/main/java/com/hai/aiknowledgebase/
│   ├── AiKnowledgeBaseApplication.java    # 启动类
│   ├── chatstore/                          # 会话持久化
│   │   └── PersistentChatMemoryStore.java  # PostgreSQL 会话存储
│   ├── common/                             # 通用组件
│   │   ├── ContentAnalyzer.java            # 内容分析器
│   │   ├── CustomDocument.java             # 自定义文档模型
│   │   ├── DocumentTypeClassifier.java     # 文档类型分类
│   │   ├── FileUtils.java                  # 文件工具类
│   │   ├── KeywordIndex.java               # BM25 倒排索引
│   │   ├── Result.java                     # 统一响应模型
│   │   └── ResultCode.java                 # 错误码枚举
│   ├── config/                             # 配置类
│   │   ├── AsyncConfig.java                # 异步线程池配置
│   │   ├── CacheConfig.java                # Caffeine 缓存配置
│   │   ├── ChatModelConfig.java            # LLM 模型配置
│   │   ├── ChunkingConfig.java             # 切片参数配置
│   │   ├── CorsConfig.java                 # 跨域配置
│   │   └── VectorStoreConfig.java          # PGVector 配置
│   ├── controller/                         # 控制器
│   │   ├── ChatController.java             # 问答接口
│   │   └── DocumentController.java         # 文档管理接口
│   ├── dto/                                # 数据传输对象
│   ├── entity/                             # 数据库实体
│   │   ├── Category.java                   # 分类
│   │   ├── ChatHistory.java                # 会话历史
│   │   ├── DocumentHash.java               # 文档哈希
│   │   └── DocumentMetadata.java           # 文档元数据
│   ├── exception/                          # 异常处理
│   │   ├── BusinessException.java          # 业务异常
│   │   └── GlobalExceptionHandler.java     # 全局异常处理器
│   ├── interfaces/                         # 接口定义
│   │   ├── IntentClassifier.java           # 意图分类器接口
│   │   └── LocalQueryRewriter.java         # 查询改写器接口
│   ├── mapper/                             # MyBatis-Plus Mapper
│   └── service/                            # 核心服务
│       ├── ChatService.java                # 问答服务（RAG 主流程）
│       ├── ChineseTokenizerService.java    # 中文分词服务
│       ├── DocumentChunkerService.java     # 文档切片服务
│       ├── DocumentHashService.java        # 哈希去重服务
│       ├── DocumentParserService.java      # 文档解析服务
│       ├── DocumentRouter.java             # 文档路由（分块策略）
│       ├── DocumentService.java            # 文档管理服务
│       ├── DocumentStatusSseService.java   # SSE 状态推送服务
│       ├── HybridSearchService.java        # 混合检索服务（BM25+向量+RRF）
│       ├── IntentRecognitionOrchestrator.java # 意图识别编排器
│       ├── LLMIntentClassifier.java        # LLM 意图分类器
│       ├── QueryCorrector.java             # 查询纠错
│       ├── QueryRewriteConfigLoader.java   # 查询改写配置加载
│       ├── QueryRewriteService.java        # 查询改写服务
│       ├── RAGSearchService.java           # RAG 检索服务
│       ├── RuleIntentClassifier.java       # 规则意图分类器
│       └── VectorizationService.java       # 异步向量化服务
├── src/main/resources/
│   ├── application.yml                     # 主配置
│   ├── application-dev.yml                 # 开发环境配置
│   ├── application-prod.yml                # 生产环境配置
│   ├── query-rewrite/                      # 查询改写规则
│   │   ├── synonyms.json                   # 同义词表
│   │   ├── fixed-mapping.json              # 固定映射
│   │   └── stopwords.json                  # 停用词表
│   └── scripts/                            # 文档解析脚本
│       ├── parse_docx.py                   # DOCX 解析
│       └── parse_pdf_cli.py                # PDF 解析
└── pom.xml
```

## 架构设计

### 文档处理流程

```
上传文件 (PDF/DOCX/MD/TXT)
    │
    ├── SHA-256 去重检查
    ├── 保存文件到磁盘
    ├── 写入元数据 (status = "processing")
    ├── 返回 docId 给前端
    │
    └── [事务提交后] 异步向量化
         ├── 文档解析 (MinerU/Python/内置)
         ├── 内容去重 (文本级 SHA-256)
         ├── 智能切片 (Markdown 递归)
         ├── 向量化存储 (PGVector)
         ├── BM25 索引 (内存倒排索引)
         ├── 更新状态 → "active"
         └── SSE 推送完成通知
```

### 检索流程

```
用户查询 "配置向量数据库"
    │
    ├── 查询改写 (同义词 + 固定映射)
    ├── 意图识别 (规则/LLM 路由)
    │
    ├── [并行] 向量检索路径
    │   └── EmbeddingModel.embed() → PGVector.search()
    │
    ├── [并行] 关键词检索路径
    │   └── jieba 分词 → BM25 倒排索引
    │
    └── RRF 融合排序 → Top-K 结果
```

### 文档状态

| 状态 | 含义 | 前端展示 |
|------|------|----------|
| `processing` | 异步向量化处理中 | 橙色旋转 + "处理中..." |
| `active` | 处理完成，可正常检索 | 绿色对勾 + "已完成" |
| `failed` | 处理失败 | 红色叉号 + 显示错误信息 |

## 配置说明

### 关键配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `document.chunk.min-tokens` | 400 | 最小切片大小 |
| `document.chunk.max-tokens` | 800 | 最大切片大小 |
| `document.chunk.overlap-ratio` | 0.2 | 切片重叠比例 |
| `document.upload.max-size-mb` | 200 | 上传文件大小上限 |
| `hybrid-search.enabled` | true | 混合检索开关 |
| `hybrid-search.rrf.k` | 60 | RRF 融合常数 |
| `query-rewrite.enabled` | true | 查询改写开关 |
| `query-rewrite.l1.confidence-threshold` | 0.85 | L1 规则置信度阈值 |

### 生产环境部署

生产环境通过环境变量注入敏感配置：

```bash
export DB_URL=jdbc:postgresql://your-host:5432/knowledge_base
export DB_USERNAME=admin
export DB_PASSWORD=your-password
export DEEPSEEK_API_KEY=your-api-key
export DOCUMENT_UPLOAD_PATH=/data/uploads
export CORS_ALLOWED_ORIGINS=https://your-domain.com
```

启动命令：

```bash
java -jar ai-knowledge-base.jar --spring.profiles.active=prod
```

## 注意事项

1. **API Key 安全**：不要将 API Key 提交到 Git 仓库，生产环境使用环境变量
2. **PGVector 扩展**：确保 PostgreSQL 已安装 PGVector 扩展，否则向量存储功能不可用
3. **文档解析依赖**：PDF 解析依赖 MinerU API，DOCX 解析依赖 Python 环境，如不可用则回退到内置简单解析器
4. **BM25 索引**：BM25 索引为内存结构，服务重启后会自动从 PGVector 重建
5. **文件大小**：上传前检查文件大小，默认上限 200MB，避免大文件撑爆内存
6. **成本控制**：注意 DeepSeek API 调用成本，合理设置 topK 参数

## License

MIT