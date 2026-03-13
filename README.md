# 代码审查 AI Agent (Code Review AI Agent)

[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://www.java.com/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-green.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.0.0--M4-purple.svg)](https://spring.io/projects/spring-ai)

专业的代码审查 AI Agent，支持项目、目录、文件和代码片段的智能代码审查，集成多种 AI 模型和静态规则引擎。

## 🚀 功能特性

### 核心功能
- **多种审查模式**：支持项目级别、目录级别、单个文件和代码片段审查
- **多 AI 模型支持**：
  - OpenAI (GPT-3.5/GPT-4)
  - DeepSeek (本地部署)
  - Qwen (通义千问)
  - 其他 OpenAI 兼容接口
- **静态规则引擎**：
  - Java: Google Java Style, Alibaba Java Coding Guidelines
  - Python: PEP 8
  - JavaScript/TypeScript: Airbnb Style Guide
- **智能 RAG 系统**：基于向量数据库实现上下文相关的代码审查
- **Web 界面**：用户友好的 Web 管理界面
- **用户权限管理**：基于 Sa-Token 的权限控制
- **Git 集成**：支持 GitCode 等代码仓库集成
- **CI/CD 集成**：支持 Webhook 触发代码审查
- **报告生成**：生成详细的代码审查报告

### 技术架构
- **后端框架**：Spring Boot 3.4.1
- **AI 框架**：Spring AI 1.0.0-M4
- **数据库**：PostgreSQL + Redis
- **向量存储**：PGVector + Spring AI Transformers
- **文件存储**：MinIO
- **前端模板**：Thymeleaf
- **权限认证**：Sa-Token

## 📦 环境要求

### 基础环境
- Java 21+
- Maven 3.6+
- PostgreSQL 12+
- Redis 6.0+
- MinIO (可选，用于文件存储)

### AI 模型选择
1. **OpenAI API**：需要 OpenAI API Key
2. **DeepSeek 本地部署**：需要安装 Ollama
3. **Qwen API**：需要阿里云 DashScope API Key
4. **其他 OpenAI 兼容接口**

## 🛠️ 快速开始

### 1. 克隆项目
```bash
git clone <repository-url>
cd code-review-ai-agent
```

### 2. 配置数据库
```sql
-- 创建数据库
CREATE DATABASE code_guardian;

-- 创建用户（可选）
CREATE USER codeguardian WITH PASSWORD 'your_password';
GRANT ALL PRIVILEGES ON DATABASE code_guardian TO codeguardian;
```

### 3. 配置应用
复制配置文件并根据需要修改：
```bash
cp src/main/resources/application.yml src/main/resources/application-dev.yml
cp src/main/resources/application.yml src/main/resources/application-prod.yml
```

#### 关键配置项：
```yaml
# 数据库配置
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/code_guardian
    username: postgres
    password: 123456

# Redis 配置
  data:
    redis:
      host: localhost
      port: 6379
      password:

# AI 模型选择（三选一）
ai:
  # OpenAI 配置
  openai:
    api-key: ${OPENAI_API_KEY}
    base-url: ${OPENAI_BASE_URL:https://api.openai.com}

  # DeepSeek/Ollama 配置
  ollama:
    base-url: ${OLLAMA_BASE_URL:http://localhost:11434}

  # Qwen 配置
  qwen:
    api-key: ${QWEN_API_KEY}
    base-url: ${QWEN_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode}

# MinIO 配置（可选）
minio:
  endpoint: ${MINIO_ENDPOINT}
  access-key: ${MINIO_ACCESS_KEY}
  secret-key: ${MINIO_SECRET_KEY}
```

### 4. 编译运行
```bash
# 编译项目
mvn clean compile

# 运行测试
mvn test

# 启动应用
mvn spring-boot:run

# 或直接运行jar
java -jar target/code-review-ai-agent-1.0.0.jar
```

应用将在 `http://localhost:7003` 启动。

## 📖 使用指南

### 1. 用户注册与登录
首次启动后，访问 Web 界面进行用户注册和登录。

### 2. 代码审查操作

#### 项目审查
1. 点击"新建审查"按钮
2. 选择"项目审查"
3. 输入 Git 仓库地址
4. 选择审查类型和 AI 模型
5. 点击"开始审查"

#### 目录审查
1. 选择已上传的项目
2. 进入项目详情页
3. 选择要审查的目录
4. 配置审查参数
5. 开始审查

#### 文件审查
在项目详情页中，点击具体文件进行单独审查。

#### 代码片段审查
1. 在首页选择"代码片段审查"
2. 输入或粘贴代码
3. 选择编程语言
4. 开始审查

### 3. 查看审查结果
- 审查完成后可以在"审查报告"页面查看详细结果
- 结果包括问题类型、严重程度、修复建议等
- 支持导出审查报告

### 4. 自定义规则
在"系统设置"中可以添加自定义审查规则：
- 选择规则模板
- 修改规则参数
- 设置规则严重程度

## 🔧 高级配置

### AI 模型配置
```yaml
# 自定义 AI 模型参数
ai:
  chat:
    options:
      model: gpt-4  # 或其他模型
      temperature: 0.3  # 创造性参数
      max-tokens: 4000  # 最大输出长度
```

### 审查规则配置
```yaml
# 自定义规则权重
review:
  rules:
    security-weight: 0.8
    performance-weight: 0.6
    style-weight: 0.4
```

### 系统优化
```yaml
# 性能优化配置
spring:
  servlet:
    multipart:
      max-file-size: 100MB
      max-request-size: 100MB
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idly: 5
```

## 📊 API 文档

### 认证接口
- `POST /api/auth/login` - 用户登录
- `POST /api/auth/register` - 用户注册

### 审查接口
- `POST /api/review/project` - 项目审查
- `POST /api/review/directory` - 目录审查
- `POST /api/review/file` - 文件审查
- `POST /api/review/snippet` - 代码片段审查

### 管理接口
- `GET /api/admin/users` - 获取用户列表
- `POST /api/admin/rules` - 添加自定义规则
- `GET /api/admin/reports` - 获取审查报告

## 🧪 测试

运行测试套件：
```bash
# 运行所有测试
mvn test

# 运行特定测试
mvn test -Dtest=CodeReviewAiAgentApplicationTests

# 生成测试报告
mvn surefire-report:report
```

## 📦 部署

### Docker 部署
```bash
# 构建镜像
mvn clean package spring-boot:build-image

# 运行容器
docker run -p 7003:7003 \
  -e DB_URL=jdbc:postgresql://host:5432/code_guardian \
  -e DB_USERNAME=postgres \
  -e DB_PASSWORD=123456 \
  -e REDIS_HOST=host \
  -e REDIS_PORT=6379 \
  code-review-ai-agent:1.0.0
```

### 传统部署
```bash
# 打包
mvn clean package

# 上传并运行
scp target/code-review-ai-agent-1.0.0.jar user@server:/path/to/
ssh user@server
java -jar code-review-ai-agent-1.0.0.jar --spring.profiles.active=prod
```

## 🔒 安全配置

1. **更改默认密码**：首次启动后立即修改默认密码
2. **配置 HTTPS**：生产环境必须使用 HTTPS
3. **API 密钥管理**：不要在代码中硬编码 API 密钥
4. **数据库安全**：使用强密码，限制数据库访问
5. **Redis 安全**：配置密码，限制网络访问

## 🤝 贡献指南

1. Fork 本项目
2. 创建功能分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 创建 Pull Request

### 开发环境设置
```bash
# 安装开发工具
mvn install

# 运行代码格式化
mvn spotless:apply

# 检查代码质量
mvn sonar:sonar
```

## 📄 许可证

本项目采用 MIT 许可证 - 查看 [LICENSE](LICENSE) 文件了解详情。

## 📞 支持

- 问题报告：[GitHub Issues](../../issues)
- 功能建议：[GitHub Discussions](../../discussions)
- 邮箱支持：[support@example.com](mailto:support@example.com)

## 🔗 相关链接

- [Spring Boot 文档](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [Spring AI 文档](https://docs.spring.io/spring-ai/reference/)
- [PostgreSQL 文档](https://www.postgresql.org/docs/)
- [Sa-Token 文档](https://sa-token.dev/)

## 📈 版本历史

### v1.0.0 (2024-02-21)
- 初始版本发布
- 支持基本的代码审查功能
- 集成多种 AI 模型
- Web 界面实现
- 用户权限管理

---

**注意**：本系统仅供学习和内部使用，请勿用于商业用途。使用前请确保遵守相关法律法规。