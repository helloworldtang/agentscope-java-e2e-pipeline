package io.agentscope.study;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * AgentScope Java 2.0 端到端 Demo：微信公众号排版 Agent。
 *
 * <p>一条流水线把 5 个能力全跑通：
 *
 * <pre>
 *  选题
 *   → ② MCP：调 exomind query/search 取素材
 *   → ③ SubAgent：委派 content-writer 把素材整合成 Markdown 正文
 *   → ③.5 @Tool estimate_readtime：算字数/阅读时长
 *   → ④ Skill wechat-format（知识）+ @Tool render_wechat_html（引擎）：排成微信内联样式 HTML
 *   → ④.5 @Tool validate_wechat_html：微信兼容性质量门
 *   → ⑤ 交付
 * </pre>
 *
 * <p>底层全靠 ReAct 的 function-call 机制：MCP / Skill / SubAgent 都通过 tool_call 触发， PublisherToolkit
 * 里那 3 个是我们亲手写的 @Tool。一个 HarnessAgent 实例 = ReAct 内核 + Harness 外壳。
 *
 * <p>模型：DeepSeek 走 OpenAI 兼容协议（agentscope-core 的 DeepSeekCredential 故意抛异常，源码钦定用
 * OpenAIChatModel 指向 deepseek base_url）。
 *
 * <p>跑法：{@code source .env && mvn exec:java} 或带选题参数 {@code mvn exec:java -Dexec.args="选题"}。
 */
public class WeChatPublisher {

    /** workspace 资源相对路径（启动时从 classpath 复制到运行目录，copy-if-absent）。 */
    private static final List<String> WORKSPACE_RESOURCES =
            List.of(
                    "AGENTS.md",
                    "tools.json",
                    "skills/wechat-format/SKILL.md",
                    "subagents/content-writer.md");

    private static final String DEFAULT_TOPIC =
            "写一篇介绍 AgentScope Java 2.0 的 Harness 工程化能力（ReActAgent + HarnessAgent + Skill + MCP"
                    + " + SubAgent）的公众号文章，面向 Java 开发者，讲清楚它和生产级 agent 框架的关系。";

    /** content-writer 子 agent 的系统提示（程序化声明，因纯 HarnessAgent 不自动扫描 subagents/*.md）。 */
    private static final String WRITER_PROMPT =
            """
            你是微信公众号【撰稿子 agent】，在独立的上下文里工作。主 agent 会给你：选题 + 素材清单 + 要求。
            你的任务：把零散素材整合成一篇连贯、结构清晰的 Markdown 正文，作为最终回复返回。

            要求：
            - 只用素材里确实有的事实，不要编造数据或引用；
            - 用 Markdown 标准子集：#/##/###、段落、**粗**、`行内代码`、代码块、> 引用、- 列表、[text](url)、--- 分割线；
            - 中文、口语化、适合公众号读者，默认 1000~1400 字；
            - 不要做排版（不加内联 CSS、不调排版工具）——那是主 agent 的活；
            - 完成后把整篇 Markdown 作为最终回复返回，不要多余解释。
            """;

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("✗ 缺少环境变量 DEEPSEEK_API_KEY（参考 .env.example）");
            System.exit(1);
        }
        String baseUrl = envOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        String modelName = envOrDefault("DEEPSEEK_MODEL", "deepseek-chat");

        // 1) DeepSeek 模型（OpenAI 兼容）
        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .modelName(modelName)
                        .stream(true)
                        .build();

        // 2) workspace 引导：classpath/resources/workspace → .agentscope/workspace（copy-if-absent）
        Path workspaceRoot = Paths.get(".agentscope", "workspace").toAbsolutePath().normalize();
        bootstrapWorkspace(workspaceRoot);

        // 3) 自研 @Tool 工具集（function call 直接展示）
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PublisherToolkit());

        // 4) SubAgent 程序化声明（content-writer 撰稿员，继承父 DeepSeek 模型）
        SubagentDeclaration writer =
                SubagentDeclaration.builder()
                        .name("content-writer")
                        .description(
                                "把选题 + 素材清单整合成结构化微信公众号 Markdown 正文。需要撰稿时委派给它。")
                        .inlineAgentsBody(WRITER_PROMPT)
                        .maxIters(12)
                        .build();

        // 5) HarnessAgent（= ReAct 内核 + Harness 外壳）：挂 workspace（自动加载 skills/、tools.json）、
        //    toolkit（3 个自研 @Tool）、subagent（content-writer，自动注册 spawn/task 委派工具）
        HarnessAgent agent =
                HarnessAgent.builder()
                        .name("wechat-publisher")
                        .sysPrompt(
                                "你是微信公众号内容编排 Agent（主编）。严格按流水线推进："
                                        + "① 调 exomind MCP(query/search) 取素材 → "
                                        + "② 委派 content-writer 子 agent 撰写 Markdown 正文 → "
                                        + "③ 调 estimate_readtime 算字数 → "
                                        + "④ 加载 wechat-format 技能后调 render_wechat_html 排版，"
                                        + "再调 validate_wechat_html 质量门 → "
                                        + "⑤ 校验通过后立刻用 write_file 把最终 HTML 写到 output/article.html，"
                                        + "再交付标题+摘要+该路径。子 agent 上下文隔离，委派时务必带全素材。全程中文。")
                        .model(model)
                        .workspace(workspaceRoot)
                        .toolkit(toolkit)
                        .subagent(writer)
                        // 无人值守 demo：BYPASS 全部放行，避免 MCP/subagent 工具卡在 HITL 确认
                        // （DONT_ASK 会把 ASK 降级成 DENY，不适用；这里要 BYPASS）
                        .permissionContext(
                                PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                        .maxIters(40)
                        .build();

        // 6) 跑一个选题（参数传入或用默认）
        String topic = args.length > 0 ? String.join(" ", args) : DEFAULT_TOPIC;
        RuntimeContext ctx =
                RuntimeContext.builder().sessionId("demo-1").userId("demo").build();

        System.out.println("================ 微信公众号排版 Agent ================");
        System.out.println("模型: " + modelName + " @ " + baseUrl);
        System.out.println("workspace: " + workspaceRoot);
        System.out.println("选题: " + topic);
        System.out.println("=====================================================\n");

        StringBuilder delivered = new StringBuilder();
        agent.streamEvents(new UserMessage(topic), ctx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent d) {
                                // 模型流式文本片段（推理过程 + 最终交付）
                                System.out.print(d.getDelta());
                                delivered.append(d.getDelta());
                            } else if (event instanceof ToolCallStartEvent t) {
                                // function call 可见性：每次工具调用实时打印
                                System.out.println("\n[🔧 工具调用] " + t.getToolCallName());
                            }
                        })
                .doOnError(e -> System.err.println("\n[✗ 异常] " + e.getMessage()))
                .blockLast();

        // 7) 落盘 agent 最终交付文本
        Path output = workspaceRoot.resolve("output").resolve("agent_deliverable.md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, delivered.toString());
        System.out.println("\n\n=====================================================");
        System.out.println("✓ 完成。Agent 交付文本已保存: " + output);
        System.out.println("  workspace 下还可见 sessions/、memory/ 等运行时产物。");
    }

    /** 把 classpath 下的 workspace 资源复制到运行目录（已存在则跳过，不覆盖用户改动）。 */
    private static void bootstrapWorkspace(Path target) throws IOException {
        Files.createDirectories(target);
        for (String rel : WORKSPACE_RESOURCES) {
            Path out = target.resolve(rel).normalize();
            if (Files.exists(out)) {
                continue; // copy-if-absent
            }
            Files.createDirectories(out.getParent());
            try (InputStream in =
                    WeChatPublisher.class.getResourceAsStream("/workspace/" + rel)) {
                if (in == null) {
                    System.err.println("⚠ 缺少 classpath 资源: /workspace/" + rel);
                    continue;
                }
                Files.copy(in, out);
            }
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
