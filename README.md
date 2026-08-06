# AgentScope Java 2.0 端到端 Pipeline：微信公众号排版 Agent

用 **AgentScope Java 2.0.0** 跑通一条端到端流水线，把 **ReActAgent + HarnessAgent + Skill + MCP + SubAgent + 自研 Function Call(@Tool)** 五大能力串在一个 pipeline 里，接 **DeepSeek**，场景是**微信公众号排版**。

## 流水线

```
选题
 → ② MCP        调 exomind 的 query/search 取素材
 → ③ SubAgent   委派 content-writer 把素材整合成 Markdown 正文（独立上下文）
 → ③.5 @Tool    estimate_readtime  算字数 / 阅读时长
 → ④ Skill      wechat-format（doocs/md 排版知识，按需加载）
    + @Tool     render_wechat_html 确定性渲染成微信内联样式 HTML
 → ④.5 @Tool    validate_wechat_html 微信兼容性质量门
 → ⑤ write_file  落本地 article.html（doocs/md 内联样式，可手动粘公众号）
 → ⑥ @Tool       publish_to_wechat 投公众号草稿箱（设 PUBLISH_ACCOUNT 才启用，否则跳过）
```

底层全靠 ReAct 的 **function call**：MCP / Skill / SubAgent 都通过 `tool_call` 触发（间接），`PublisherToolkit` 里 4 个 `@Tool` 是我们亲手写的（直接）。

## 能力对照

| 能力 | 在 pipeline 里的落点 | 关键文件 |
|---|---|---|
| **ReActAgent + HarnessAgent** | 一个 `HarnessAgent` 实例 = ReAct 内核 + Harness 外壳 | `WeChatPublisher.java` |
| **DeepSeek 接入** | `OpenAIChatModel` 指向 deepseek base_url | `WeChatPublisher.java` |
| **Skill** | `wechat-format`（doocs/md 排版知识，workspace 自动加载） | `workspace/skills/wechat-format/SKILL.md` |
| **MCP** | exomind `mcp` stdio 服务（workspace `tools.json` 自动加载） | `workspace/tools.json` |
| **SubAgent** | `content-writer` 撰稿员（程序化声明，自动注册 spawn 委派工具） | `WeChatPublisher.java` + `workspace/subagents/content-writer.md` |
| **Function Call (@Tool)** | `estimate_readtime` / `render_wechat_html` / `validate_wechat_html` / `publish_to_wechat` | `PublisherToolkit.java` |
| **发布闭环（可选）** | `publish_to_wechat` → exomind `POST /drafts` 注入正文 + `draft wechat` 调微信草稿箱（`PUBLISH_ACCOUNT` 门控） | `PublisherToolkit.java` |

## 几个源码核实出来的关键决策（避坑）

1. **DeepSeek 不能用 `.model("deepseek:...")`**：`agentscope-core` 的 `DeepSeekCredential.getChatModelClass()` 故意抛 `UnsupportedOperationException`，源码注释钦定「用 `OpenAIChatModel` 指向 DeepSeek base URL」。所以 pipeline 用 `OpenAIChatModel.builder().apiKey(...).baseUrl("https://api.deepseek.com").modelName("deepseek-chat")`。

2. **排版引擎是 Java `@Tool` 而不是 shell 脚本**：`HarnessAgent` 默认**只在沙箱文件系统下注册 shell 工具**（源码 `HarnessAgent.java:2326`），本地运行没有 shell，跑不了 python 脚本。所以把 doocs/md 规则的确定性渲染实现为 `render_wechat_html` Java 工具，Skill 负责知识、@Tool 负责引擎。

3. **SubAgent 必须程序化声明**：纯 `HarnessAgent.builder().workspace()` **不会**自动扫描 `subagents/*.md`（`build()` 里 `subagentDeclarations` 仅由 `.subagent(...)` 填充）。所以 pipeline 在 Java 里用 `SubagentDeclaration.builder()...inlineAgentsBody(...)` 声明 content-writer；`subagents/content-writer.md` 仅作人类可读规格存档。

4. **workspace 引导**：skills / tools.json 由 HarnessAgent 从 workspace 自动加载，但 workspace 必须是可写路径（要写 session/memory/产物）。pipeline 启动时把 `resources/workspace/*` 以 copy-if-absent 复制到 `.agentscope/workspace/`。

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

# 闭环投递到公众号草稿箱：设 PUBLISH_ACCOUNT 后，pipeline 末尾自动调 publish_to_wechat
# export PUBLISH_ACCOUNT=ailang   # ailang/danxin/mingdeng，需 exomind 已 login
```

运行时会实时打印模型的流式文本和每次 `🔧 工具调用`（function call 可见）。产物：
- `.agentscope/workspace/publisher/output/article.html` —— doocs/md 内联样式 HTML（可手动粘公众号）
- `.agentscope/workspace/output/agent_deliverable.md` —— agent 最终交付文本
- 设了 `PUBLISH_ACCOUNT` 时，还会把 Markdown 正文投到对应公众号草稿箱（不群发，后台手动发）

> **发布闭环说明**：`publish_to_wechat` 发给 exomind 的是 **Markdown 正文**（exomind 负责最终渲染 + AI 出封面 + 调微信）；本地 `article.html` 是我们 `render_wechat_html` 的 doocs/md 排版产物，供手动粘贴/核对，两者渲染路径独立。

## 依赖

- `io.agentscope:agentscope-harness:2.0.0`（Maven Central，自动带 `agentscope-core`）
- `io.agentscope:agentscope-extensions-model-openai:2.0.0`（DeepSeek 兼容）
- `io.modelcontextprotocol.sdk:mcp:0.17.0`（exomind MCP stdio 客户端）
- `org.commonmark:commonmark:0.22.0`（render_wechat_html 的 Markdown 解析）

## exomind：个人知识库 + 公众号写作投递引擎

本 pipeline 的 MCP 素材源用的是 **[exomind](https://youhuale.cn)**（网站：https://youhuale.cn ）——一个个人知识库引擎：

- **知识库**：通过 CLI / MCP / Skill 多层接入，把笔记、调研、经验沉淀成可检索的知识图谱；本 pipeline 里 agent 就是通过 exomind MCP 的 `query` / `search` 工具去取素材的（`workspace/tools.json` 里配的 `exomind mcp` stdio 服务）。
- **公众号写作投递**：内置写作引擎（基于知识库素材 + 号调性生成草稿）和投递链路（AI 出封面 + 调微信草稿箱），把「取素材 → 写稿 → 排版 → 投递」闭环。
- 安装：`npm install -g exomind` 后 `exomind install` 配置 MCP/skill。

> 本 pipeline 只用了 exomind 的 MCP 取素材能力；排版用的是自带 Skill + 自研 @Tool，投递未在本 pipeline 工程内闭环。

## 平台下发 skill（动态分发 demo）

上面的 `wechat-format` 是**预置** skill（workspace 目录、build 时静态加载）。生产里更常见的是「agent 管理平台维护 skill 存储 → agent 拉取」的动态分发。AgentScope 把 skill 来源抽象成 `AgentSkillRepository`，官方提供 **Git / MySQL / PostgreSQL / Nacos** 等后端——docs 原话：**MySQL / PostgreSQL / Nacos 支持「管理后台 / 配置中心动态修改、立即生效」**。

本 demo 用 **Git 仓库**做最轻量的平台下发演示（无需 DB / Nacos 基础设施）：

```bash
# ① 初始化本地 git skill 仓（=「平台」维护的 skill 存储），下发初始 skill A
bash setup-skill-store.sh
export SKILL_GIT_URL=file:///Users/you/path/to/agentscope-skill-store   # 用脚本输出的 URL

# ② agent 从平台拉取 skill（GitSkillRepository，autoSync=true，每次读检查远端 HEAD 变化才 pull）
PRINT_SKILLS_ONLY=1 mvn exec:java
# → 📦 平台(git)下发的 skill: [platform-greet]

# ③ 平台再下发一个 skill：往仓里 commit 一个新 SKILL.md（模拟控制台发布）
cd $SKILL_STORE && mkdir -p skills/platform-recap && \
  printf -- '---\nname: platform-recap\ndescription: 一句回顾\n---\n# 回顾\n' \
  > skills/platform-recap/SKILL.md && git add -A && git commit -m "platform: 下发 platform-recap"

# ④ agent 再拉一次 → 拿到新 skill
PRINT_SKILLS_ONLY=1 mvn exec:java
# → 📦 平台(git)下发的 skill: [platform-greet, platform-recap]
```

设了 `SKILL_GIT_URL`（且不设 `PRINT_SKILLS_ONLY`）时，平台下发的 skill 会和 workspace 预置 skill 一起挂到 HarnessAgent，agent 即可按需加载使用。

> **动态化三层次**（源码核实）：① **预置**——workspace 静态加载；② **平台下发**——Git/MySQL/PG/**Nacos** 仓，agent 每次 build/session 拉取，平台改了下次生效（Nacos 带 listener/`AiService` 最接近实时推送）；③ **agent 自助创建**——`enableSkillManageTool` → `skill_manage`（`SkillManageTool.java:256`，6 个 action），运行时 CRUD，但同会话 catalog 静态、要下次 build 才进目录。换后端只换 `AgentSkillRepository` 实现，agent 主流程不动。

## 升级到生产形态（换仓库后端）

上面的 demo 用 Git 仓做平台下发。生产里更常见的是「管理后台在线编辑 skill、改完即生效」——**只换 `AgentSkillRepository` 实现，agent 主流程一行不改**：

**MySQL（管理后台在线运营，改完即生效）**

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-skill-mysql-repository</artifactId>
  <version>${agentscope.version}</version>
</dependency>
```

```java
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.skill.repository.mysql.MysqlSkillRepository;

HikariDataSource ds = new HikariDataSource();
ds.setJdbcUrl("jdbc:mysql://localhost:3306/agentscope");
ds.setUsername("root");
ds.setPassword("***");
// 第二参数 createIfNotExist=true：自动建库建表
AgentSkillRepository repo = new MysqlSkillRepository(ds, true);
```

**PostgreSQL**：把上面换成 `PostgresSkillRepository`（构造同 `(DataSource, boolean)`），依赖 `agentscope-extensions-skill-postgresql-repository`。

**Nacos（配置中心，最接近实时推送）**：依赖 `agentscope-extensions-nacos-skill`。Nacos 2.x+ 的 `AiService` 原生支持 skill/工具注册，配合 listener 可做到平台发布 → agent 近实时感知。

```java
import com.alibaba.nacos.api.ai.AiService;
import io.agentscope.core.nacos.skill.NacosSkillRepository;

AiService aiService = /* Nacos AI 服务客户端 */;
AgentSkillRepository repo = new NacosSkillRepository(aiService, "namespace-id");
```

**挂到 agent（三种后端都一样）**：

```java
HarnessAgent agent = HarnessAgent.builder()
        .name("wechat-publisher")
        // ... 其余配置不变
        .skillRepository(repo)   // 平台下发的 skill 从这里来
        .build();
```

> **选型**：Git = 版本管控 / PR review；MySQL / PG = 管理后台在线编辑、可与业务数据同事务；**Nacos = 配置中心动态下发，带 listener 最接近实时推送**。docs 原话：「MySQL / PostgreSQL / Nacos 动态修改、立即生效」。换后端只换 repo 实现——这就是这套架构解耦的关键，也是企业级 agent 平台「控制平面管 Skill」的落地形态。

## 排版 skill 来源

排版知识参考 GitHub 上 star >1K 的 [**doocs/md**](https://github.com/doocs/md)（~12.9K star，最主流的微信 Markdown 排版器）的内联样式规则，编码进 `wechat-format` skill 与 `render_wechat_html` 工具。
