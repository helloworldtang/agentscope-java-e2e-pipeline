package io.agentscope.study;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * 自研 Function Call 工具集（@Tool 注解驱动）。
 *
 * <p>本 demo 里 function-call 能力的「直接自研」展示。注意它和 MCP/Skill/SubAgent 的关系： 后三者都
 * 依靠 function-call 这个底层机制运行（间接），而本类是我们亲手写的 @Tool（直接）。 三个工具都是
 * 纯 Java、确定性、零网络——所以也是验证 function-call 闭环的稳定锚点。
 *
 * <p>为什么排版引擎是 Java @Tool 而不是 shell 脚本： HarnessAgent 默认只在沙箱文件系统下注册 shell
 * 工具（源码 HarnessAgent:2326），本地运行没有 shell，跑不了 python 脚本； 因此把「doocs/md 规则
 * 的确定性渲染」实现为 Java @Tool，Skill 负责知识、@Tool 负责引擎。
 */
public class PublisherToolkit {

    // ===== doocs/md 风格的内联样式（微信公众号只认 inline style） =====
    private static final String S_H1 =
            "margin:30px 0 20px;font-size:22px;font-weight:bold;text-align:center;color:#1a1a1a";
    private static final String S_H2 =
            "margin:28px 0 16px;font-size:18px;font-weight:bold;color:#1a1a1a;"
                    + "border-left:4px solid #3f51b5;padding-left:10px;"
                    + "border-bottom:1px solid #eee;padding-bottom:6px";
    private static final String S_H3 =
            "margin:24px 0 12px;font-size:16px;font-weight:bold;color:#3f51b5";
    private static final String S_P =
            "margin:0 0 16px;line-height:1.75;font-size:15px;color:#3f3f3f";
    private static final String S_QUOTE =
            "margin:16px 0;padding:10px 14px;border-left:4px solid #3f51b5;"
                    + "background:#f7f7f7;color:#666;font-size:14px";
    private static final String S_UL =
            "margin:0 0 16px;padding-left:22px;line-height:1.75;font-size:15px;color:#3f3f3f";
    private static final String S_LI = "margin:4px 0";
    private static final String S_HR = "border:none;border-top:1px solid #ddd;margin:24px 0";
    private static final String S_STRONG = "color:#3f51b5;font-weight:bold";
    private static final String S_A =
            "color:#3f51b5;text-decoration:none;border-bottom:1px solid #3f51b5";
    private static final String S_INLINE_CODE =
            "background:#f2f2f2;padding:2px 6px;border-radius:3px;font-size:13px;color:#c7254e";
    private static final String S_PRE =
            "margin:16px 0;padding:14px;background:#282c34;border-radius:5px;"
                    + "overflow-x:auto;line-height:1.5";
    private static final String S_PRE_CODE =
            "background:none;padding:0;color:#abb2bf;font-family:Menlo,Monaco,Consolas,monospace;"
                    + "font-size:13px;white-space:pre";
    private static final String S_IMG = "max-width:100%;border-radius:4px;margin:12px auto;display:block";

    /** 工具一：估算阅读时长（用于公众号摘要「本文约 X 字，预计阅读 Y 分钟」）。 */
    @Tool(
            name = "estimate_readtime",
            description =
                    "统计中文 Markdown 正文的字数与预计阅读时长（分钟），用于生成公众号摘要。"
                            + "输入 Markdown 正文，返回 '字数=N，预计阅读 M 分钟'。")
    public String estimateReadtime(
            @ToolParam(name = "markdown", description = "要统计的 Markdown 正文") String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "字数=0，预计阅读 0 分钟";
        }
        // 去掉 Markdown 标记与空白，按可见字符计字数
        String visible = markdown.replaceAll("[#>*`~\\[\\]()!|\\-+=\\s]", "");
        int chars = visible.length();
        int minutes = Math.max(1, (int) Math.round(chars / 400.0)); // 中文阅读 ~400 字/分钟
        return String.format("字数=%d，预计阅读 %d 分钟", chars, minutes);
    }

    /**
     * 工具二：把 Markdown 确定性渲染成微信兼容的内联样式 HTML（doocs/md 风格）。
     *
     * <p>实现：commonmark 解析 → 渲染基础 HTML → 给关键标签注入 inline style。 不依赖 LLM 手写 CSS，
     * 产出稳定可复现。
     */
    @Tool(
            name = "render_wechat_html",
            description =
                    "把 Markdown 正文渲染成微信公众号兼容的、带 doocs/md 风格内联样式的 HTML。"
                            + "产出可直接粘贴到公众号后台。排版环节请调用本工具，不要手写 CSS。")
    public String renderWechatHtml(
            @ToolParam(name = "markdown", description = "要排版的 Markdown 正文") String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "<!-- empty markdown -->";
        }
        Parser parser = Parser.builder().build();
        Node document = parser.parse(markdown);
        String html = HtmlRenderer.builder().build().render(document);

        // 行内 <code> 必须先于 <pre><code> 处理：先把 pre 里的 code 占位保护起来
        // 简单稳健起见：先给 pre 整体加样式，再把 pre 内部的 <code> 替换为深色版
        html = addStyleToTag(html, "pre", S_PRE);
        html = html.replaceAll(
                "(?i)(<pre[^>]*>)\\s*<code>", "$1<code style=\"" + S_PRE_CODE + "\">");

        html = addStyleToTag(html, "blockquote", S_QUOTE);
        html = addStyleToTag(html, "h1", S_H1);
        html = addStyleToTag(html, "h2", S_H2);
        html = addStyleToTag(html, "h3", S_H3);
        html = addStyleToTag(html, "p", S_P);
        html = addStyleToTag(html, "ul", S_UL);
        html = addStyleToTag(html, "ol", S_UL);
        html = addStyleToTag(html, "li", S_LI);
        html = addStyleToTag(html, "strong", S_STRONG);
        html = addStyleToTag(html, "code", S_INLINE_CODE); // 剩余的行内 code
        html = addStyleToSelfClosing(html, "hr", S_HR);
        html = addStyleToImg(html, S_IMG);
        html = addStyleToAnchor(html, S_A);

        return html.trim();
    }

    /** 工具三：微信兼容性质量门——检测会被公众号过滤或导致样式丢失的元素。 */
    @Tool(
            name = "validate_wechat_html",
            description =
                    "校验 HTML 的微信公众号兼容性：检测 <style>/<script>/<iframe>/外链样式表/class 等会被"
                            + "过滤或失效的元素。排版后调用做质量门，返回 OK 或问题清单。")
    public String validateWechatHtml(
            @ToolParam(name = "html", description = "待校验的 HTML 字符串") String html) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return "FAIL: HTML 为空";
        }
        String lower = html.toLowerCase();
        if (lower.contains("<style")) {
            errors.add("发现 <style> 标签：公众号会剥离，必须改为 inline style");
        }
        if (lower.contains("<script")) {
            errors.add("发现 <script>：公众号禁止脚本");
        }
        if (lower.contains("<iframe")) {
            errors.add("发现 <iframe>：公众号不支持");
        }
        if (lower.contains("rel=\"stylesheet\"") || lower.contains("<link")) {
            errors.add("发现外链样式表 <link rel=stylesheet>：无法生效");
        }
        if (lower.contains("class=\"") || lower.contains("class='")) {
            warnings.add("发现 class 属性：公众号会剥离 class，样式应全部走 inline style");
        }
        if (lower.contains("style=\"") || lower.contains("style='")) {
            // 有 inline style，符合预期
        } else if (!errors.isEmpty()) {
            warnings.add("未检测到 inline style，排版可能不生效");
        }

        StringBuilder sb = new StringBuilder();
        if (errors.isEmpty() && warnings.isEmpty()) {
            sb.append("OK: 通过微信兼容性校验，含 inline style，无禁止元素。");
        } else {
            if (!errors.isEmpty()) {
                sb.append("FAIL，硬伤 ").append(errors.size()).append(" 项：\n");
                errors.forEach(e -> sb.append("  - ").append(e).append('\n'));
            } else {
                sb.append("PASS-WITH-WARNINGS（无硬伤，但有提示）：\n");
            }
            warnings.forEach(w -> sb.append("  ⚠ ").append(w).append('\n'));
        }
        return sb.toString().trim();
    }

    /**
     * 工具四：把文章投递到公众号草稿箱，闭环流水线「发」的一端。
     *
     * <p>复用 exomind：① POST /drafts 注入 Markdown 正文（CLI 没有 import 正文这步走 HTTP） ② 子进程调
     * {@code exomind draft wechat} 复用其「AI 出封面 + 调微信」链路。 受环境变量
     * {@code PUBLISH_ACCOUNT} 门控：未设置则 SKIP，只留本地 article.html，避免每次跑都往草稿箱堆文章。
     *
     * <p>注意：发给 exomind 的是 Markdown 正文（exomind 负责最终渲染+封面+调微信）；本地
     * article.html 是我们 render_wechat_html 的 doocs/md 排版产物，供手动粘贴/核对。
     */
    @Tool(
            name = "publish_to_wechat",
            description =
                    "把文章投递到公众号草稿箱（经 exomind：注入正文+AI出封面+调微信，不群发）。"
                            + "需先设置环境变量 PUBLISH_ACCOUNT（公众号名，如 ailang）；未设置则自动跳过。"
                            + "返回 media_id（前缀 T1NF 表示真投成功）。标题用文章主标题，markdown 用完整 Markdown 正文。")
    public String publishToWechat(
            @ToolParam(name = "title", description = "文章标题") String title,
            @ToolParam(name = "markdown", description = "完整 Markdown 正文") String markdown) {
        String account = System.getenv("PUBLISH_ACCOUNT");
        if (account == null || account.isBlank()) {
            return "SKIP: 未设置 PUBLISH_ACCOUNT 环境变量，发布已跳过（仅产出本地 article.html）。"
                    + "如需投递，设置 PUBLISH_ACCOUNT=<公众号名> 后重跑。";
        }
        try {
            // 读 exomind 凭据（~/.exomind/config.json）
            Path cfg = Paths.get(System.getProperty("user.home"), ".exomind", "config.json");
            if (!Files.isRegularFile(cfg)) {
                return "SKIP: 未找到 ~/.exomind/config.json（exomind 未登录），发布已跳过。";
            }
            ObjectMapper om = new ObjectMapper();
            JsonNode cred = om.readTree(Files.readString(cfg));
            String base = cred.get("base_url").asText();
            String apiKey = cred.get("api_key").asText();

            // ① POST /drafts 注入 Markdown 正文
            ObjectNode body = om.createObjectNode();
            body.put("title", title);
            body.put("content", markdown);
            body.put("target_account", account);
            body.put("topic", title);
            body.set("tags", om.createArrayNode().add("AgentScope Java").add("Agent"));

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest req =
                    HttpRequest.newBuilder()
                            .uri(URI.create(base + "/drafts"))
                            .timeout(java.time.Duration.ofSeconds(30))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)))
                            .build();
            HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                return "FAIL: POST /drafts HTTP " + res.statusCode() + " "
                        + res.body().substring(0, Math.min(200, res.body().length()));
            }
            String draftId = om.readTree(res.body()).get("id").asText();

            // ② exomind draft wechat <id> --account <account>（AI 异步出封面 + 调微信，落到草稿箱不群发）
            Process process =
                    new ProcessBuilder(
                                    "exomind", "draft", "wechat", draftId, "--account", account)
                            .redirectErrorStream(true)
                            .start();
            boolean done = process.waitFor(3, TimeUnit.MINUTES);
            String out =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!done) {
                process.destroyForcibly();
                return "FAIL: exomind draft wechat 超时 3min。output: "
                        + out.substring(0, Math.min(300, out.length()));
            }
            Matcher m = Pattern.compile("media_id[:：]\\s*([A-Za-z0-9_-]+)").matcher(out);
            if (!m.find()) {
                return "FAIL: 未解析到 media_id。output: "
                        + out.substring(0, Math.min(300, out.length()));
            }
            String mediaId = m.group(1);
            boolean published = mediaId.startsWith("T1NF");
            return String.format(
                    "已投递到【%s】草稿箱（不群发，需后台手动发）。media_id=%s，published=%s"
                            + "（T1NF 前缀=真投成功）。草稿 id=%s。后台: 内容与互动→草稿箱",
                    account, mediaId, published, draftId);
        } catch (Exception e) {
            return "ERROR: 发布失败：" + e.getMessage();
        }
    }

    // ===== 内联样式注入辅助 =====

    /** 给形如 {@code <tag>} 的开标签加 style（commonmark 默认不在这些标签上输出属性）。 */
    private static String addStyleToTag(String html, String tag, String style) {
        // 仅匹配没有已存在属性的裸开标签：<tag>
        Pattern p = Pattern.compile("(?i)<(" + tag + ")>(?!\\s*<code>)");
        // 上面 (?!\\s*<code>) 仅对 pre 有意义，对其它标签无害（其它标签后很少紧跟 <code>）
        Matcher m = p.matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, "<" + m.group(1) + " style=\"" + style + "\">");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 给自闭合标签 {@code <hr />} 或 {@code <hr>} 加样式。 */
    private static String addStyleToSelfClosing(String html, String tag, String style) {
        return html.replaceAll(
                "(?i)<(" + tag + ")(\\s*/)?>", "<$1 style=\"" + style + "\"$2>");
    }

    /** 给 {@code <img src=... />} 加样式（保留原有属性）。 */
    private static String addStyleToImg(String html, String style) {
        return html.replaceAll(
                "(?i)<img((?:(?!style=)[^>])*)\\/?>",
                "<img$1 style=\"" + style + "\"/>");
    }

    /** 给 {@code <a href=...>} 加样式（保留 href）。 */
    private static String addStyleToAnchor(String html, String style) {
        return html.replaceAll(
                "(?i)<a(\\s+href=\"[^\"]*\")>", "<a$1 style=\"" + style + "\">");
    }
}
