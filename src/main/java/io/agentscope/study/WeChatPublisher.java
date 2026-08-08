package io.agentscope.study;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * AgentScope Java 2.0 端到端 Pipeline：微信公众号排版 Agent 的入口（编排层）。
 *
 * <p>main 只负责编排：加载配置 {@link PipelineConfig} → 引导 workspace → 用 {@link AgentFactory} 装配
 * agent（try-with-resources 管理资源）→ 运行（多轮 REPL 或单跑一个选题）。模型/工具/平台下发/子 agent/
 * 资源生命周期都收在 AgentFactory 里。
 *
 * <p>跑法：{@code source .env && mvn exec:java}（默认单跑）；{@code INTERACTIVE=1 mvn exec:java}（多轮）；
 * {@code -Dexec.args="选题"}（自带选题）。
 */
public final class WeChatPublisher {

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

    public static void main(String[] args) throws IOException {
        PipelineConfig config;
        try {
            config = PipelineConfig.load();
        } catch (IllegalStateException e) {
            System.err.println("✗ " + e.getMessage());
            System.exit(1);
            return;
        }

        Path workspaceRoot = Paths.get(".agentscope", "workspace").toAbsolutePath().normalize();
        bootstrapWorkspace(workspaceRoot);

        // AgentFactory 持有 RedisClient/GitSkillRepository 等资源，try-with-resources 保证释放
        try (AgentFactory factory = AgentFactory.create(config)) {
            if (config.printSkillsOnly) {
                System.out.println("(PRINT_SKILLS_ONLY=1 → 只验证平台下发，不跑 agent)");
                System.out.println("📦 平台(git)下发的 skill: " + factory.platformSkillNames());
                System.out.println("   toolkit 已注册工具: " + factory.toolkitToolNames());
                return;
            }

            HarnessAgent agent = factory.build(workspaceRoot);
            RuntimeContext ctx =
                    RuntimeContext.builder()
                            .sessionId(config.sessionId)
                            .userId("publisher")
                            .build();

            System.out.println("================ 微信公众号排版 Agent ================");
            System.out.println("模型: " + config.deepseekModel + " @ " + config.deepseekBaseUrl);
            System.out.println("workspace: " + workspaceRoot);
            System.out.println(
                    "sessionId: "
                            + config.sessionId
                            + "（同 id 跨轮/跨重启记得上下文；REDIS_STATE=1 时存 Redis）");
            System.out.println("=====================================================\n");

            if (config.interactive) {
                interactiveRepl(agent, ctx);
                return;
            }
            String topic = args.length > 0 ? String.join(" ", args) : DEFAULT_TOPIC;
            System.out.println("选题: " + topic + "\n");
            StringBuilder delivered = new StringBuilder();
            replyOnce(agent, ctx, new UserMessage(topic), delivered);
            writeDeliverable(workspaceRoot, delivered.toString());
        }
    }

    /** 单轮回复：流式打印模型文本 + 工具调用，累计交付文本。AgentState 在结束时自动写回。 */
    private static void replyOnce(
            HarnessAgent agent, RuntimeContext ctx, UserMessage msg, StringBuilder delivered) {
        agent.streamEvents(msg, ctx)
                .doOnNext(
                        event -> {
                            if (event instanceof TextBlockDeltaEvent d) {
                                System.out.print(d.getDelta());
                                delivered.append(d.getDelta());
                            } else if (event instanceof ToolCallStartEvent t) {
                                System.out.println("\n[🔧 工具调用] " + t.getToolCallName());
                            }
                        })
                .doOnError(e -> System.err.println("\n[✗ 异常] " + e.getMessage()))
                .blockLast();
    }

    /**
     * 交互式多轮 REPL：同一 sessionId 循环，跨轮（甚至跨进程重启，REDIS_STATE=1 时）记得上下文。
     * 适合「写稿 → 改标题 → 加段落」连续 refine。输 exit 退出。
     */
    private static void interactiveRepl(HarnessAgent agent, RuntimeContext ctx) throws IOException {
        System.out.println(
                "交互多轮模式（sessionId="
                        + ctx.getSessionId()
                        + "）。输入选题/修改指令多轮 refine；输 exit 退出。");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
        while (true) {
            System.out.print("\n你: ");
            String line = reader.readLine();
            if (line == null || line.trim().equalsIgnoreCase("exit")) {
                System.out.println("\n再见。会话状态已持久化，下次同 sessionId 继续。");
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            System.out.print("\nAgent: ");
            replyOnce(agent, ctx, new UserMessage(line.trim()), new StringBuilder());
        }
    }

    private static void writeDeliverable(Path workspaceRoot, String text) throws IOException {
        Path output = workspaceRoot.resolve("output").resolve("agent_deliverable.md");
        Files.createDirectories(output.getParent());
        Files.writeString(output, text);
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

    private WeChatPublisher() {}
}
