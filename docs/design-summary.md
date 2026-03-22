# CodeGuardian AI Code Review Agent — 完整设计与实现总结

> 文档日期：2026-03-20
> 版本：v2.0（Diff-based 全链路重构）

---

## 一、整体架构

```
Git 平台 (GitCode/GitLab)
    │
    ├── Webhook 事件 ──→ WebhookController
    │                        │ 极速接收，Redis Lua 防抖，MQ 投递，立即 200 OK
    │                        ↓
    │                   webhook.queue
    │                        │
    │                        ↓
    │                   WebhookMessageConsumer
    │                        │ 惰性丢弃 → 拉 Diff → ChangeAnalyzer
    │                        │ → AI 审查 → QualityGateService → 写回 PR 评论
    │
    └── CI/CD 流水线 ──→ CicdController
                             │ 生成 taskKey，Redis 初始化 PENDING，MQ 投递，毫秒级返回
                             ↓
                        cicd.queue
                             │
                             ↓
                        CicdMessageConsumer
                             │ 拉 Diff → ChangeAnalyzer → AI 审查
                             │ → QualityGateService → 写 DB → 写 Redis gate 状态
                             ↓
                     流水线轮询 GET /api/v1/cicd/status/{taskKey}
```

---

## 二、Webhook 链路完整流程

### 2.1 Controller 层（极速防抖）

1. 收到 GitCode Webhook（Merge Request Hook）
2. 过滤非 MR 事件、非目标操作（open/update/reopen）
3. 解析关键字段：`gitUrl`、`mrIid`、`commitHash`、`commitTimestamp`、**`prDescription`**（MR 正文）
4. Redis Lua 脚本防抖：基于 committer date 比较时间戳，保证 Redis 中始终是最新 commit
5. 投递 `webhook.queue`，立即返回 HTTP 200

### 2.2 Consumer 层（核心处理）

```
1. 惰性丢弃：consumer 侧再比对 Redis 最新 hash，不一致直接 ACK 丢弃
2. updateStatus → pending（Git commit 状态）
3. DiffFetchService 拉取 MR Diff（不 clone，REST API 内存操作）
4. ChangeAnalyzer.analyze(diffs) → List<ChangeUnit>
5. 注入 prDescription 到所有 ChangeUnit（优先 PR 描述，降级 commitMessage）
6. 遍历 ChangeUnit：
   - 命中语义指纹缓存 → 直接复用 Finding
   - DELETE 场景 → DeletePromptStrategy
   - ADD/MODIFY   → WebhookPromptStrategy
   - 调用 AI → 解析 Finding → 写指纹缓存（METHOD_LEVEL ADD/MODIFY）
7. 最后一个 unit 完成 → SETNX 锁（防并发）→ hasComment 幂等检查
8. QualityGateService.checkQualityGate(allFindings, "HIGH")
9. 构建 Markdown 报告 → postComment → updateStatus(success/failed)
```

### 2.3 异常处理

```
异常
 ├── retryCount < 3 → webhook.retry.exchange（5min TTL）→ 回 webhook.queue
 └── retryCount >= 3 → basicNack → webhook.dlx → webhook.dlq（人工介入）
```

---

## 三、CI/CD 链路完整流程

### 3.1 触发（毫秒级）

```
POST /api/v1/cicd/trigger
  body: { gitUrl, mrIid, commitHash, commitMessage, blockOn, triggerBy }
  ↓
生成 taskKey = "CI-{triggerBy}-{timestamp}"
写 Redis: cicd:task:{taskKey}:status = PENDING
投递 cicd.queue
返回 { taskKey, status: "PENDING" }
```

### 3.2 流水线轮询

```
GET /api/v1/cicd/status/{taskKey}?blockOn=HIGH
  ↓
读 Redis 状态（不查 DB，抗高并发）
  ├── 非终态（PENDING/PARSING/REVIEWING）→ 直接返回状态
  └── 终态（COMPLETED/FAILED/TIMEOUT）
        ├── 读 reviewTaskId
        ├── QualityGateService.checkQualityGate(taskId, blockOn)
        └── 返回 { passed, summary, reportUrl }
```

### 3.3 Consumer 层

```
1. setStatus → PARSING
2. 拉取 MR Diff
3. ChangeAnalyzer → List<ChangeUnit>
4. 注入 commitMessage 为 prDescription
5. 全部缓存命中 → 跳过 AI
6. setStatus → REVIEWING
7. 遍历 ChangeUnit → reviewUnitWithRetry（本地重试 3 次，间隔 2s）
   ⚠ 不用延迟队列：流水线有时间限制，5min 延迟会导致流水线超时
8. 写 DB（ReviewTask + Finding）
9. QualityGateService → 写 Redis gate 状态（PASSED/BLOCKED）
10. setStatus → COMPLETED
```

### 3.4 异常处理

```
单元审查失败 → 本地重试 3 次（间隔 2s，总耗时 <10s）→ 跳过该单元（不中断整体）
Consumer 整体异常 → setStatus FAILED → basicNack → cicd.dlx → cicd.dlq（独立 DLQ）
```

---

## 四、ChangeAnalyzer 分流决策树

### 4.1 阈值定义

| 参数 | 值 | 说明 |
|---|---|---|
| `METHOD_COUNT_THRESHOLD` | 5 | 变更方法数超过此值 |
| `CHANGE_RATIO_THRESHOLD` | 30% | 变化行数/文件总行数超过此值 |

**双条件 AND**：方法数多 AND 改动比例高，才走整体路径（爆炸式变更）

### 4.2 ADD / MODIFY 决策

```
解析所有方法（JavaParser AST / 正则降级）
   │
   ├── 交集方法为空（成员变量/包声明/注解）
   │     → FILE_LEVEL，riskLevel 由变化行数决定
   │     → RAG query：filePath + prDescription + diff前300字
   │
   └── 交集方法不空
         ├── 方法数 > 5 AND 变化行比例 > 30%（爆炸式）
         │     → FILE_LEVEL，riskLevel = HIGH，无指纹
         │     → RAG query：className + 所有方法签名 + prDescription
         │
         └── 常规变更
               → 每方法独立 METHOD_LEVEL
               → 语义指纹查缓存：命中 → fromCache=true；未命中 → RAG
               → riskLevel 由方法行数决定
               → RAG query：className + methodSignature + prDescription + 方法体前200字
```

### 4.3 DELETE 决策

```
FULL_DELETE
   ├── 无方法 / 方法数 > 5 → FILE_LEVEL，无指纹
   └── 方法数 ≤ 5 → 每方法 METHOD_LEVEL，无指纹（删除不缓存）

PARTIAL_DELETE → 同 MODIFY 逻辑（取旧代码解析）
```

### 4.4 风险等级

| RiskLevel | 变化行数（FILE_LEVEL） | 方法行数（METHOD_LEVEL） |
|---|---|---|
| LOW | < 20 行 | < 30 行 |
| MEDIUM | 20 - 100 行 | 30 - 80 行 |
| HIGH | > 100 行 / 爆炸式 | > 80 行 |

---

## 五、语义指纹策略

**核心决策：指纹只做方法粒度（METHOD_LEVEL），整体路径（FILE_LEVEL）永远不做指纹。**

原因：整体指纹 = 所有方法脱水拼接的 SHA-256，任意一个方法微调指纹全部失效，实际命中率趋近于 0。

```
指纹 key 格式：diff:fingerprint:{sha256}:{model}:{language}:{kbVersion}
TTL：24 小时
kbVersion：知识库更新时 INCR，使旧缓存自然失效
```

**指纹生效场景：** METHOD_LEVEL + ADD/MODIFY（删除不缓存，复用价值为零）

---

## 六、RAG Query 三模板

| 场景 | Query 构成 |
|---|---|
| METHOD_LEVEL | `Language + Class + Method签名 + prDescription + 方法体前200字` |
| FILE_LEVEL（有方法） | `Language + Class + 所有变更方法签名列表 + prDescription` |
| FILE_LEVEL（无方法） | `Language + filePath + prDescription + diff文本前300字` |

**为什么 prDescription 必须进 query：**
知识库规范文档（如"支付接口必须幂等"）可能只有业务语义词，方法体代码里没有，PR 描述里有。不拼入 prDescription 会导致召回率大幅下降。

---

## 七、Prompt 三种策略

### 7.1 WebhookPromptStrategy（Markdown 输出）

Section 顺序：
1. 角色设定 + 防幻觉 Guardrails
2. **审查强度**（动态：变更规模 + riskLevel → 审查松紧度文字）
3. 变更上下文（文件路径、变更类型、语言、commit message）
4. **开发意图**（prDescription 不空时注入，引导实现与意图一致性审查）
5. AST 信息：METHOD_LEVEL = 单方法详情；FILE_LEVEL = 所有变更方法概览列表
6. Diff 片段（`+/-` 格式）
7. 完整代码（含行号）
8. RAG 知识库片段
9. JSON 输出格式要求

### 7.2 CicdPromptStrategy（严格 JSON 输出）

紧凑 KV 格式，无 Markdown 装饰，机器可读：
```
FILE / CHANGE_TYPE / LANGUAGE / REVIEW_SCOPE
RISK_LEVEL / CHANGED_UNITS / PR_INTENT / COMMIT_MSG
METHOD / CLASS / LINES（METHOD_LEVEL）
或 CHANGED_METHODS 列表（FILE_LEVEL）
DIFF / CODE / STANDARDS
OUTPUT_FORMAT（示例 JSON）
```

### 7.3 DeletePromptStrategy（删除场景专用）

与 WebhookPromptStrategy 的核心区别——审查目标变为：
1. 调用方是否已同步清理？
2. 是否引入悬挂引用或资源泄漏？
3. 接口实现是否已同步移除？
4. 是否破坏 API 兼容性？

触发条件：`ChangeType == FULL_DELETE || PARTIAL_DELETE`

---

## 八、RabbitMQ 拓扑

```
webhook.exchange (direct)
    → webhook.queue
        [x-dead-letter-exchange = webhook.dlx]
        [x-message-ttl = 30min]
        → 失败(retryCount<3) → webhook.retry.exchange
                                → webhook.retry.queue [TTL=5min]
                                → 到期回 webhook.exchange → webhook.queue
        → 失败(retryCount>=3) → basicNack → webhook.dlx → webhook.dlq

cicd.exchange (direct)
    → cicd.queue
        [x-dead-letter-exchange = cicd.dlx]   ← 独立 DLX，不共用 Webhook
        [x-message-ttl = 30min]
        → 整体异常 → basicNack → cicd.dlx → cicd.dlq
        （单元失败走本地快速重试，不进 MQ）
```

**为什么 CI/CD 不用延迟重试队列：**
流水线有 timeout 限制（如 Jenkins stage timeout 10min），消息在延迟队列躺 5min + 处理时间容易超过流水线 timeout，导致流水线以 TIMEOUT 强制终止而非拿到审查结果。本地快速重试（3次 × 2s = <10s）流水线无感知。

**为什么 CI/CD 独立 DLQ：**
人工介入时需要区分死信来源，共用 DLQ 无法判断是 Webhook 还是 CI/CD 的失败。

---

## 九、QualityGateService 接入

| 场景 | 调用方式 | blockOn 来源 |
|---|---|---|
| Webhook 回写 | `checkQualityGate(allFindings, "HIGH")` | 硬编码默认值 |
| CI/CD 消费者 | `checkQualityGate(allFindings, message.getBlockOn())` | 流水线 POST 时传入 |
| CI/CD 状态轮询 | `checkQualityGate(taskId, blockOn)` | HTTP 请求参数（默认 CRITICAL） |

门禁结果写入 Redis：`cicd:task:{taskKey}:gate = PASSED / BLOCKED`

---

## 十、新增/修改文件清单

| 文件 | 类型 | 说明 |
|---|---|---|
| `model/ReviewScope.java` | 新增 | METHOD_LEVEL / FILE_LEVEL 枚举 |
| `model/RiskLevel.java` | 新增 | LOW / MEDIUM / HIGH 枚举 |
| `model/ChangeUnit.java` | 修改 | 新增 reviewScope / riskLevel / prDescription / allChangedMethods |
| `service/diff/ChangeAnalyzer.java` | 修改 | 完整重写：阈值分流 + RAG query 三模板 + 死代码清理 |
| `prompt/WebhookPromptStrategy.java` | 修改 | 新增审查强度、开发意图、FILE_LEVEL 方法概览 section |
| `prompt/CicdPromptStrategy.java` | 修改 | 新增 RISK_LEVEL / PR_INTENT 等字段 |
| `prompt/DeletePromptStrategy.java` | 新增 | 删除场景独立 Prompt 策略 |
| `mq/WebhookMessage.java` | 修改 | 新增 prDescription 字段 |
| `mq/CicdMessage.java` | 修改 | 新增 commitMessage / blockOn / retryCount |
| `mq/WebhookMessageConsumer.java` | 修改 | 注入 prDescription / DeletePromptStrategy / QualityGateService |
| `mq/CicdMessageConsumer.java` | 修改 | 注入 commitMessage / 本地重试 / QualityGateService / 独立 DLQ |
| `config/RabbitMQConfig.java` | 修改 | CI/CD 独立 DLX/DLQ |
| `controller/WebhookController.java` | 修改 | 解析 prDescription 字段并写入消息体 |
| `service/integration/QualityGateService.java` | 修改 | checkQualityGate(List, String) 改为 public |
