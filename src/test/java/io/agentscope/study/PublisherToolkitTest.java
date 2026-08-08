package io.agentscope.study;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link PublisherToolkit} 三个确定性 @Tool 的单元测试（render/validate/estimate 都是纯函数，
 * 零网络，最适合单测）。{@code publish_to_wechat} 走 HTTP+子进程，不在单测范围。
 */
class PublisherToolkitTest {

    private final PublisherToolkit tools = new PublisherToolkit();

    // ===== estimate_readtime =====

    @Test
    void estimateReadtime_emptyReturnsZero() {
        assertEquals("字数=0，预计阅读 0 分钟", tools.estimateReadtime(""));
        assertEquals("字数=0，预计阅读 0 分钟", tools.estimateReadtime(null));
    }

    @Test
    void estimateReadtime_countsVisibleChars() {
        // "你好世界" 无 markdown 标记 → 4 可见字符；4/400 向上取整保底 1 分钟
        String r = tools.estimateReadtime("你好世界");
        assertTrue(r.contains("字数=4"), "应统计出 4 个可见字符，实际: " + r);
        assertTrue(r.contains("预计阅读 1 分钟"), r);
    }

    @Test
    void estimateReadtime_stripsMarkdownMarkers() {
        // 标记字符（# * ` 等）不计入字数
        String r = tools.estimateReadtime("# 标题\n\n**粗** `code`");
        // "标题粗code" = 3 中文 + 4 英文 = 7 个可见字符
        assertTrue(r.contains("字数=7"), "应剔除 markdown 标记后计 7 字，实际: " + r);
    }

    // ===== render_wechat_html =====

    @Test
    void renderWechatHtml_emptyReturnsPlaceholder() {
        assertEquals("<!-- empty markdown -->", tools.renderWechatHtml(""));
        assertEquals("<!-- empty markdown -->", tools.renderWechatHtml(null));
    }

    @Test
    void renderWechatHtml_injectsInlineStyles() {
        String md = "# 标题\n\n正文段。\n";
        String html = tools.renderWechatHtml(md);
        // 微信只认 inline style：h1 / p 必须带 style
        assertTrue(html.contains("<h1 style=\""), "h1 应注入 inline style: " + html);
        assertTrue(html.contains("<p style=\""), "p 应注入 inline style: " + html);
    }

    @Test
    void renderWechatHtml_codeBlockGetsDarkTheme() {
        String html = tools.renderWechatHtml("```\ncode\n```\n");
        assertTrue(html.contains("<pre style=\""), "pre 应有样式: " + html);
        assertTrue(html.contains("#282c34"), "代码块应用深色背景: " + html);
    }

    // ===== validate_wechat_html =====

    @Test
    void validateWechatHtml_rejectsEmpty() {
        assertEquals("FAIL: HTML 为空", tools.validateWechatHtml(""));
    }

    @Test
    void validateWechatHtml_passesCleanInlineStyledHtml() {
        String clean = "<h1 style=\"color:red\">t</h1><p style=\"x\">body</p>";
        String r = tools.validateWechatHtml(clean);
        assertTrue(r.startsWith("OK"), "干净的内联样式 HTML 应通过: " + r);
        assertFalse(r.contains("FAIL"), r);
    }

    @Test
    void validateWechatHtml_flagsScriptAsFail() {
        String r = tools.validateWechatHtml("<p style=\"x\">hi</p><script>bad()</script>");
        assertTrue(r.contains("FAIL"), "含 <script> 必须 FAIL: " + r);
        assertTrue(r.contains("script"), r);
    }

    @Test
    void validateWechatHtml_warnsOnClassAndMissingInlineStyle() {
        // 有 class（公众号会剥离）+ 无 inline style → 两条 warning
        String r = tools.validateWechatHtml("<p class=\"a\">hi</p>");
        assertTrue(r.contains("class"), "应警告 class: " + r);
        assertTrue(r.contains("inline style"), "应警告缺 inline style: " + r);
    }
}
