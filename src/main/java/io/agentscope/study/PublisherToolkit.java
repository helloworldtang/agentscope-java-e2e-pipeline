package io.agentscope.study;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.ArrayList;
import java.util.List;
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
