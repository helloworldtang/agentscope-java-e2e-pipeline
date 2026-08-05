---
name: wechat-format
description: Use this skill when you need to typeset/render a Markdown article into WeChat public-account compatible HTML (微信公众号排版). Triggers on tasks like "排版"、"转成公众号 HTML"、"render for wechat"。Loads the doocs/md-style inline-CSS rules and tells you which deterministic tools to call.
---

# 微信公众号排版 Skill（doocs/md 风格）

本 skill 提供**排版知识**（doocs/md 的内联样式规则），具体**渲染由确定性 @Tool 执行**。

## 为什么是「知识 + 工具」两层

微信公众号编辑器会**剥离 `<style>` 标签、外链 CSS、`<script>`、`<iframe>`**，只认**标签上的内联 style**。
所以正确的产出是：每个标签自带 `style="..."` 的 HTML。

- 本 SKILL.md = 排版**知识**（你应该产出什么样的样式）；
- `render_wechat_html` @Tool = 排版**引擎**（你调用它，它按下面的规则确定性产出 HTML）。

## 正确做法（必走）

1. 调用 `render_wechat_html` 工具，把 Markdown 正文交给它，它会按 doocs/md 规则产出带内联样式的 HTML；
2. 调用 `validate_wechat_html` 工具校验微信兼容性；
3. 校验通过即交付；不通过则回到第 1 步调整 Markdown 后重渲。

**不要**自己手写一整段 CSS，**不要**输出带 `<style>` 或外链样式表的 HTML。

## doocs/md 排版规则（参考，用于理解工具产出）

`render_wechat_html` 已内置以下风格，你只需理解、不必手写：

- **标题**：h1 居中加粗、h2 左侧色条 + 底部细线、h3 加粗带强调色；
- **正文**：行高 1.75、字号 15px、段落间距适中、颜色 `#3f3f3f`；
- **引用**：左侧 4px 色条 + 浅灰底；
- **代码**：行内 code 用浅底圆角胶囊；代码块 pre 用深色底 + 等宽字体；
- **强调**：strong 用主题色而非纯黑；列表项紧凑；
- **分割线 / 图片 / 链接**：均有适配微信的间距与颜色。

## 输入约定

- 传入的 Markdown 应使用标准子集：`#`/`##`/`###` 标题、段落、`**粗体**`、`` `行内代码` ``、代码块、`>` 引用、`-` 列表、链接 `[t](url)`、`---` 分割线。
- 渲染产物是**可直接粘贴到公众号后台**的单段 HTML 字符串。
