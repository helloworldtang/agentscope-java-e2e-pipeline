# AgentScope Java 2.0 端到端 Demo：微信公众号排版 Agent

用 **AgentScope Java 2.0.0** 跑通一条端到端流水线，把 **ReActAgent + HarnessAgent + Skill + MCP + SubAgent + 自研 Function Call(@Tool)** 五大能力串在一个 demo 里，接 **DeepSeek**，场景是**微信公众号排版**。

## 流水线

```
选题
 → ② MCP        调 exomind 的 query/search 取素材
 → ③ SubAgent   委派 content-writer 把素材整合成 Markdown 正文（独立上下文）
 → ③.5 @Tool    estimate_readtime  算字数 / 阅读时长
 → ④ Skill      wechat-format（doocs/md 排版知识，按需加载）
    + @Tool     render_wechat_html 确定性渲染成微信内联样式 HTML
 → ④.5 @Tool    validate_wechat_html 微信兼容性质量门
 → ⑤ 交付标题 + 摘要 + HTML
```

底层全靠 ReAct 的 **function call**：MCP / Skill / SubAgent 都通过 `tool_call` 触发（间接），`PublisherToolkit` 里 3 个 `@Tool` 是我们亲手写的（直接）。

## 能力对照

| 能力 | 在 demo 里的落点 | 关键文件 |
|---|---|---|
| **ReActAgent + HarnessAgent** | 一个 `HarnessAgent` 实例 = ReAct 内核 + Harness 外壳 | `WeChatPublisher.java` |
| **DeepSeek 接入** | `OpenAIChatModel` 指向 deepseek base_url | `WeChatPublisher.java` |
| **Skill** | `wechat-format`（doocs/md 排版知识，workspace 自动加载） | `workspace/skills/wechat-format/SKILL.md` |
| **MCP** | exomind `mcp` stdio 服务（workspace `tools.json` 自动加载） | `workspace/tools.json` |
| **SubAgent** | `content-writer` 撰稿员（程序化声明，自动注册 spawn 委派工具） | `WeChatPublisher.java` + `workspace/subagents/content-writer.md` |
| **Function Call (@Tool)** | `estimate_readtime` / `render_wechat_html` / `validate_wechat_html` | `PublisherToolkit.java` |

## 几个源码核实出来的关键决策（避坑）

1. **DeepSeek 不能用 `.model("deepseek:...")`**：`agentscope-core` 的 `DeepSeekCredential.getChatModelClass()` 故意抛 `UnsupportedOperationException`，源码注释钦定「用 `OpenAIChatModel` 指向 DeepSeek base URL」。所以 demo 用 `OpenAIChatModel.builder().apiKey(...).baseUrl("https://api.deepseek.com").modelName("deepseek-chat")`。

2. **排版引擎是 Java `@Tool` 而不是 shell 脚本**：`HarnessAgent` 默认**只在沙箱文件系统下注册 shell 工具**（源码 `HarnessAgent.java:2326`），本地运行没有 shell，跑不了 python 脚本。所以把 doocs/md 规则的确定性渲染实现为 `render_wechat_html` Java 工具，Skill 负责知识、@Tool 负责引擎。

3. **SubAgent 必须程序化声明**：纯 `HarnessAgent.builder().workspace()` **不会**自动扫描 `subagents/*.md`（`build()` 里 `subagentDeclarations` 仅由 `.subagent(...)` 填充）。所以 demo 在 Java 里用 `SubagentDeclaration.builder()...inlineAgentsBody(...)` 声明 content-writer；`subagents/content-writer.md` 仅作人类可读规格存档。

4. **workspace 引导**：skills / tools.json 由 HarnessAgent 从 workspace 自动加载，但 workspace 必须是可写路径（要写 session/memory/产物）。demo 启动时把 `resources/workspace/*` 以 copy-if-absent 复制到 `.agentscope/workspace/`。

## 跑法

前置：
- JDK 17+、Maven 3.9+
- 已安装 exomind CLI（`which exomind` 能找到，提供 `exomind mcp` stdio 服务）
- DeepSeek API Key

```bash
cp .env.example .env
# 编辑 .env 填入 DEEPSEEK_API_KEY
source .env

# 编译
mvn -q compile

# 跑（默认选题：介绍 AgentScope Java Harness）
mvn exec:java

# 或自带选题
mvn exec:java -Dexec.args="写一篇讲 RAG 在企业落地踩坑的公众号文章"
```

运行时会实时打印模型的流式文本和每次 `🔧 工具调用`（function call 可见）。Agent 最终交付文本写到 `.agentscope/workspace/output/agent_deliverable.md`。

## 依赖

- `io.agentscope:agentscope-harness:2.0.0`（Maven Central，自动带 `agentscope-core`）
- `io.agentscope:agentscope-extensions-model-openai:2.0.0`（DeepSeek 兼容）
- `io.modelcontextprotocol.sdk:mcp:0.17.0`（exomind MCP stdio 客户端）
- `org.commonmark:commonmark:0.22.0`（render_wechat_html 的 Markdown 解析）

## exomind：个人知识库 + 公众号写作投递引擎

本 demo 的 MCP 素材源用的是 **[exomind](https://youhuale.cn)**（网站：https://youhuale.cn ）——一个个人知识库引擎：

- **知识库**：通过 CLI / MCP / Skill 多层接入，把笔记、调研、经验沉淀成可检索的知识图谱；本 demo 里 agent 就是通过 exomind MCP 的 `query` / `search` 工具去取素材的（`workspace/tools.json` 里配的 `exomind mcp` stdio 服务）。
- **公众号写作投递**：内置写作引擎（基于知识库素材 + 号调性生成草稿）和投递链路（AI 出封面 + 调微信草稿箱），把「取素材 → 写稿 → 排版 → 投递」闭环。
- 安装：`npm install -g exomind` 后 `exomind install` 配置 MCP/skill。

> 本 demo 只用了 exomind 的 MCP 取素材能力；排版用的是自带 Skill + 自研 @Tool，投递未在本 demo 工程内闭环。

## 排版 skill 来源

排版知识参考 GitHub 上 star >1K 的 [**doocs/md**](https://github.com/doocs/md)（~12.9K star，最主流的微信 Markdown 排版器）的内联样式规则，编码进 `wechat-format` skill 与 `render_wechat_html` 工具。
