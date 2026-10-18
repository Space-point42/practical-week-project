import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js/lib/common'

/**
 * Markdown 渲染器。
 *
 * 出于安全考虑关闭了 HTML 直通（html: false）：
 * 知识内容由多人协作产生，若允许内联 HTML，
 * 任何编辑者都能注入脚本影响其他用户 —— 这是典型的存储型 XSS 场景。
 * 需要富文本时应走白名单清洗，而不是直接放行原始 HTML。
 */
const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  typographer: false,
  highlight(code, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return `<pre class="hljs"><code>${hljs.highlight(code, { language: lang }).value}</code></pre>`
      } catch {
        /* 落到下面的兜底分支 */
      }
    }
    return `<pre class="hljs"><code>${md.utils.escapeHtml(code)}</code></pre>`
  }
})

// 让外链在新标签页打开，并阻断 opener 引用（防 tab-nabbing）
const defaultLinkOpen =
  md.renderer.rules.link_open ||
  ((tokens, idx, options, env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const href = tokens[idx].attrGet('href') || ''
  if (/^https?:\/\//i.test(href)) {
    tokens[idx].attrSet('target', '_blank')
    tokens[idx].attrSet('rel', 'noopener noreferrer')
  }
  return defaultLinkOpen(tokens, idx, options, env, self)
}

export function renderMarkdown(text) {
  if (!text) return ''
  return md.render(text)
}

/** 提取纯文本，用于字数统计与摘要 */
export function markdownToPlain(text) {
  if (!text) return ''
  return md
    .render(text)
    .replace(/<[^>]+>/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

export default md
