package io.agentscope.study;

import java.util.Objects;

/**
 * 从环境变量加载的运行配置（集中一处，避免散落在 main 里）。
 *
 * <p>所有「开关 + 凭据 + 路径」都从这里取，main 只负责编排。env 未设时给生产合理的默认值。
 */
public final class PipelineConfig {

    public final String deepseekKey;
    public final String deepseekBaseUrl;
    public final String deepseekModel;

    /** 平台下发：git skill 仓 URL（file:// 或 https://）。null → 退回 workspace 静态加载。 */
    public final String skillGitUrl;
    public final boolean mcpFromPlatform;
    public final boolean printSkillsOnly;

    public final boolean redisState;
    public final String sessionId;
    public final boolean interactive;

    private PipelineConfig(
            String deepseekKey,
            String deepseekBaseUrl,
            String deepseekModel,
            String skillGitUrl,
            boolean mcpFromPlatform,
            boolean printSkillsOnly,
            boolean redisState,
            String sessionId,
            boolean interactive) {
        this.deepseekKey = deepseekKey;
        this.deepseekBaseUrl = deepseekBaseUrl;
        this.deepseekModel = deepseekModel;
        this.skillGitUrl = skillGitUrl;
        this.mcpFromPlatform = mcpFromPlatform;
        this.printSkillsOnly = printSkillsOnly;
        this.redisState = redisState;
        this.sessionId = sessionId;
        this.interactive = interactive;
    }

    /** 从环境变量加载；DEEPSEEK_API_KEY 必填，缺失抛 IllegalStateException（由调用方转成退出提示）。 */
    public static PipelineConfig load() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("缺少环境变量 DEEPSEEK_API_KEY（参考 .env.example）");
        }
        return new PipelineConfig(
                key,
                envOrDefault("DEEPSEEK_BASE_URL", "https://api.deepseek.com"),
                envOrDefault("DEEPSEEK_MODEL", "deepseek-chat"),
                System.getenv("SKILL_GIT_URL"),
                flag("MCP_FROM_PLATFORM"),
                flag("PRINT_SKILLS_ONLY"),
                flag("REDIS_STATE"),
                envOrDefault("SESSION_ID", "publisher-1"),
                flag("INTERACTIVE"));
    }

    private static boolean flag(String name) {
        return "1".equals(System.getenv(name));
    }

    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }

    // 复用：DeepSeek 是否配置就绪（key 非空即就绪，load() 已保证）
    public boolean isPlatformSkillEnabled() {
        return skillGitUrl != null && !skillGitUrl.isBlank();
    }

    @Override
    public String toString() {
        return "PipelineConfig{model=" + deepseekModel + " @ " + deepseekBaseUrl
                + ", session=" + sessionId
                + ", platformSkill=" + isPlatformSkillEnabled()
                + ", mcpFromPlatform=" + mcpFromPlatform
                + ", redis=" + redisState + ", interactive=" + interactive + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PipelineConfig that)) return false;
        return Objects.equals(deepseekKey, that.deepseekKey)
                && Objects.equals(deepseekBaseUrl, that.deepseekBaseUrl)
                && Objects.equals(deepseekModel, that.deepseekModel)
                && Objects.equals(skillGitUrl, that.skillGitUrl)
                && mcpFromPlatform == that.mcpFromPlatform
                && printSkillsOnly == that.printSkillsOnly
                && redisState == that.redisState
                && Objects.equals(sessionId, that.sessionId)
                && interactive == that.interactive;
    }

    @Override
    public int hashCode() {
        return Objects.hash(deepseekKey, deepseekBaseUrl, deepseekModel, skillGitUrl, mcpFromPlatform,
                printSkillsOnly, redisState, sessionId, interactive);
    }
}
