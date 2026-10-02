export function readActivityTranslations(value: string | Record<string, any> | undefined, defaults: Record<string, any>) {
  if (!value) return structuredClone(defaults)
  const parsed = typeof value === 'string' ? JSON.parse(value) : structuredClone(value)
  if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object') throw new Error('旧活动文案格式无效，请先修复，原数据未覆盖')
  return parsed
}

export function validateActivityCopy(copies: Record<string, any>, fallback: string) {
  if (!copies[fallback]) throw new Error('请选择已配置的回退语言')
  for (const [locale, copy] of Object.entries(copies)) {
    if (!/^[a-z]{2}(-[A-Za-z]{2,4})?$/.test(locale) || !copy || typeof copy !== 'object') throw new Error('语言格式无效')
    for (const key of ['title', 'body', 'terms']) {
      if (typeof copy[key] !== 'string' || !copy[key].trim() || copy[key].length > (key === 'title' ? 160 : 10000)) throw new Error(`${locale} 的标题、正文、细则不能为空或超长`)
    }
    for (const key of ['open', 'close', 'claim', 'success']) if (copy[key] != null && (typeof copy[key] !== 'string' || copy[key].length > 200)) throw new Error(`${locale} 的按钮或提示超长`)
  }
}

// Only update exact legacy copy bindings. Custom layout text and styles remain authoritative.
export function syncActivityCopy(pages: any[], copy: Record<string, string>, changes: Record<string, string>) {
  for (const [key, text] of Object.entries(changes)) {
    const old = copy[key]
    if (old === text) continue
    const visit = (nodes: any[]) => nodes.forEach(node => {
      if (old && node.text === old) {
        node.text = text
        if (node.children?.every((run: any) => run.type === 'text')) node.children = [{ type: 'text', text, style: node.children[0]?.style }]
      } else visit(node.children || [])
    })
    pages.forEach(page => visit(page.nodes))
    copy[key] = text
  }
}
