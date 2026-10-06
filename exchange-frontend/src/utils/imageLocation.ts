/** Uploaded files are private and same-origin. Legacy remote upload URLs never receive credentials. */
export function imageLocation(value: string | null | undefined, origin: string, mode?: string): string {
  if (!value) return ''
  if (/^(blob:|data:image\/(png|jpeg|gif|webp);)/i.test(value)) return value
  let url: URL
  try { url = new URL(value.startsWith('/') || /^https?:/i.test(value) ? value : '/uploads/images/' + value, origin) } catch { return '' }
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password) return ''
  if (/\/(?:api\/)?(?:demo-)?uploads\/(?:images|audio)\//.test(url.pathname)) {
    if (url.origin !== origin || url.search || url.hash) return ''
    if (url.pathname.startsWith('/demo-uploads/')) return url.pathname
    return mode === 'DEMO' ? url.pathname.replace(/^\/api/, '').replace('/uploads/', '/demo-uploads/') : '/api' + url.pathname.replace(/^\/api/, '')
  }
  if (url.origin !== origin) return url.href // Public external image only; no bearer attached.
  return (url.pathname.startsWith('/market/icons/') ? '/api' + url.pathname : url.pathname) + url.search + url.hash
}
export function privateImagePath(value: string, origin: string): string | null {
  const normalized = imageLocation(value, origin)
  return normalized.startsWith('/api/uploads/images/') || normalized.startsWith('/api/uploads/audio/') || normalized.startsWith('/demo-uploads/images/') || normalized.startsWith('/demo-uploads/audio/') ? normalized : null
}
