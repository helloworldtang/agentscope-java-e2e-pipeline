package io.agentscope.study;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.GitSkillRepository;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.redis.state.RedisAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.McpServerRegistrar;
import io.lettuce.core.RedisClient;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 构建 {@link HarnessAgent} 并管理其底层资源生命周期（{@link AutoCloseable}）。
 *
 * <p>把「模型 + 工具集 + 平台下发(skill/mcp) + 子 agent + agent 装配 + Redis/Git 资源」从 main 里收拢到一处；
 * main 只负责编排（config → workspace → build → run）。{@link #close()} 保证 Lettuce 客户端和 Git 仓库被释放，
 * 修掉之前 RedisClient/GitSkillRepository 泄漏的问题。
 */
public final class AgentFactory implements AutoCloseable {

    /** 主 agent 系统提示：严格按流水线推进 5 步（含 3.5 动态 skill）。 */
    static final String SYSTEM_PROMPT =
            "你是微信公众号内容编排 Agent（主编）。严格按流水线推进："
                    + "① 调 exomind MCP(query/search) 取素材 → "
                    + "② 委派 content-writer 子 agent 撰写 Markdown 正文 → "
                    + "③ 调 estimate_readtime 算字数 → "
                    + "③.5 动态 skill 演示：用 skill_manage(action=create) 创建一个"
                    + " wechat-cta skill（content 为公众号结尾 CTA 写作要领），"
                    + "再 load_skill_through_path 加载它，按要领给正文补结尾 CTA → "
                    + "④ 加载 wechat-format 技能后调 render_wechat_html 排版，"
                    + "再调 validate_wechat_html 质量门 → "
                    + "⑤ 用 write_file 把最终 HTML 写到 output/article.html，"
                    + "再调 publish_to_wechat 把标题+Markdown 正文投递到公众号草稿箱（未设"
                    + " PUBLISH_ACCOUNT 会自动跳过、仅留本地文件），最后交付标题+摘要+路径。"
                    + "子 agent 上下文隔离，委派时务必带全素材。全程中文。";

    /** content-writer 子 agent 提示（程序化声明，纯 HarnessAgent 不自动扫描 subagents/*.md）。 */
    static final String WRITER_PROMPT =
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

    private final PipelineConfig config;
    private final OpenAIChatModel model;
    private final Toolkit toolkit;
    private final SubagentDeclaration writer;
    private final GitSkillRepository platformRepo; // nullable
    private final RedisClient redisClient; // nullable

    private AgentFactory(
            PipelineConfig config,
            OpenAIChatModel model,
            Toolkit toolkit,
            SubagentDeclaration writer,
            GitSkillRepository platformRepo,
            RedisClient redisClient) {
        this.config = config;
        this.model = model;
        this.toolkit = toolkit;
        this.writer = writer;
        this.platformRepo = platformRepo;
        this.redisClient = redisClient;
    }

    /**
     * 创建工厂：构建模型 + 工具集（含平台下发的 MCP）+ 子 agent；按需拉起 Redis 客户端。
     * 平台 skill 仓也在此创建（便于 {@link #platformSkillNames()} 校验 + 后续 close）。
     */
    public static AgentFactory create(PipelineConfig config) throws IOException {
        OpenAIChatModel model =
                OpenAIChatModel.builder()
                        .apiKey(config.deepseekKey)
                        .baseUrl(config.deepseekBaseUrl)
                        .modelName(config.deepseekModel)
                        .stream(true)
                        .build();

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new PublisherToolkit());

        GitSkillRepository platformRepo = null;
        if (config.isPlatformSkillEnabled()) {
            platformRepo =
                    new GitSkillRepository(config.skillGitUrl, null, null, "platform-git", true);
            // 仅当需要把 MCP 也从平台下发时，读 mcp-servers.json 注册进 toolkit
            if (config.mcpFromPlatform) {
                registerPlatformMcp(toolkit, config.skillGitUrl);
            }
        }

        SubagentDeclaration writer =
                SubagentDeclaration.builder()
                        .name("content-writer")
                        .description("把选题 + 素材清单整合成结构化微信公众号 Markdown 正文。需要撰稿时委派给它。")
                        .inlineAgentsBody(WRITER_PROMPT)
                        .maxIters(12)
                        .build();

        RedisClient redisClient =
                config.redisState ? RedisClient.create("redis://localhost:6379") : null;

        return new AgentFactory(config, model, toolkit, writer, platformRepo, redisClient);
    }

    /** 平台下发的 skill 名单（供 PRINT_SKILLS_ONLY 校验）。无平台仓时返回空列表。 */
    public List<String> platformSkillNames() {
        if (platformRepo == null) {
            return List.of();
        }
        return platformRepo.getAllSkills().stream().map(AgentSkill::getName).sorted().toList();
    }

    /** toolkit 已注册的工具名（供 PRINT_SKILLS_ONLY 校验 MCP 是否注册成功）。 */
    public List<String> toolkitToolNames() {
        return List.copyOf(toolkit.getToolNames());
    }

    /** 装配并返回 {@link HarnessAgent}（workspace 驱动，挂 skill/mcp/stateStore/compaction）。 */
    public HarnessAgent build(Path workspaceRoot) {
        HarnessAgent.Builder b =
                HarnessAgent.builder()
                        .name("wechat-publisher")
                        .sysPrompt(SYSTEM_PROMPT)
                        .model(model)
                        .workspace(workspaceRoot)
                        .toolkit(toolkit)
                        .subagent(writer)
                        // 动态 skill：agent 可运行时自助 create/edit/delete skill
                        .enableSkillManageTool(true)
                        // 无人值守：BYPASS 全部放行，避免 MCP/subagent 卡 HITL（DONT_ASK 会降级 DENY，不能用）
                        .permissionContext(
                                PermissionContextState.builder().mode(PermissionMode.BYPASS).build())
                        // 长期记忆：超 30 条压缩蒸馏 → MEMORY.md，下轮注入
                        .compaction(
                                CompactionConfig.builder().triggerMessages(30).keepMessages(10).build());
        if (platformRepo != null) {
            b.skillRepository(platformRepo);
        }
        if (config.mcpFromPlatform) {
            b.disableToolsConfig(); // workspace tools.json 不自动加载，MCP 只从平台来
        }
        if (redisClient != null) {
            b.stateStore(
                    RedisAgentStateStore.builder()
                            .lettuceClient(redisClient)
                            .keyPrefix("agentscope:wechat-publisher:")
                            .build());
        }
        return b.maxIters(40).build();
    }

    /** 读平台仓里的 mcp-servers.json，把 MCP server 注册进 toolkit（阻塞）。 */
    private static void registerPlatformMcp(Toolkit toolkit, String skillGitUrl) throws IOException {
        Path mcpJson = Paths.get(URI.create(skillGitUrl)).resolve("mcp-servers.json");
        if (!Files.isRegularFile(mcpJson)) {
            System.out.println("⚠ MCP_FROM_PLATFORM=1 但平台仓无 mcp-servers.json，跳过平台 MCP");
            return;
        }
        JsonNode root = Json.MAPPER.readTree(Files.readString(mcpJson));
        JsonNode serversNode = root.has("mcpServers") ? root.get("mcpServers") : root;
        Map<String, McpServerConfig> servers =
                Json.MAPPER.convertValue(serversNode, new TypeReference<Map<String, McpServerConfig>>() {});
        System.out.println("📦 平台(git)下发的 MCP: " + servers.keySet());
        McpServerRegistrar.register(toolkit, servers);
    }

    @Override
    public void close() {
        // 反向释放：先 Redis，再 Git 仓。避免资源泄漏（之前 RedisClient/GitRepo 在正常路径不关闭）。
        if (redisClient != null) {
            redisClient.shutdown();
        }
        if (platformRepo != null) {
            platformRepo.close();
        }
    }
}
