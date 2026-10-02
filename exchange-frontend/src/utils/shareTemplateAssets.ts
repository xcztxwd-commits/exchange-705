import { shareImagePath, type ShareDesign } from './shareTemplateDesign.ts'

const imageTypes = ['image/png', 'image/jpeg', 'image/gif', 'image/webp']
export async function fetchShareImage(src: string, token: string | null): Promise<Blob> {
  if (!shareImagePath(src)) throw new Error('素材图片地址无效')
  // Follow the stored file namespace, not the currently selected trading account mode.
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(src, { headers: token ? { Authorization: `Bearer ${token}` } : {}, credentials: 'omit', cache: 'no-store', redirect: 'error', signal: controller.signal })
    if (!response.ok) throw new Error('素材图片不可用，请重新选择素材或重试')
    return await response.blob()
  } finally { clearTimeout(timer) }
}
// Each caller owns its cache. Never retain protected files across accounts or tenants.
export function createShareAssetLoader(fetchBlob: (src: string) => Promise<Blob>, identity: () => string) {
  const cache = new Map<string, Promise<HTMLImageElement>>(), urls = new Set<string>()
  let owner = identity(), disposed = false, revision = 0
  function clear() { revision++; cache.clear(); for (const url of urls) URL.revokeObjectURL(url); urls.clear() }
  return {
    async images(design?: ShareDesign) {
      if (owner !== identity()) { clear(); owner = identity() }
      if (disposed) throw new Error('素材预览已关闭')
      const run = revision, sources = [...new Set((design?.decorations || []).filter(layer => layer.type === 'image' && layer.visible).map(layer => layer.src!))]
      if (sources.length > 16 || sources.some(src => !shareImagePath(src))) throw new Error('素材图片地址无效')
      const entries = await Promise.all(sources.map(async src => {
        if (!cache.has(src)) cache.set(src, (async () => {
          const blob = await fetchBlob(src)
          if (disposed || run !== revision || owner !== identity()) throw new Error('登录状态已变化，请重新预览')
          if (!(blob instanceof Blob) || !imageTypes.includes(blob.type.split(';')[0]!) || !blob.size || blob.size > 5 * 1024 * 1024) throw new Error('素材图片格式或大小无效')
          const url = URL.createObjectURL(blob); urls.add(url)
          try {
            const image = new Image(); image.src = url; await image.decode()
            if (!image.naturalWidth || !image.naturalHeight || image.naturalWidth * image.naturalHeight > 20000000) throw new Error('素材图片尺寸过大或无效')
            return image
          }
          catch (error) { URL.revokeObjectURL(url); urls.delete(url); throw error }
        })().catch(error => { if (run === revision) cache.delete(src); throw error }))
        return [src, await cache.get(src)!] as const
      }))
      if (disposed || run !== revision || owner !== identity()) throw new Error('登录状态已变化，请重新预览')
      return new Map(entries)
    },
    dispose() { disposed = true; clear() },
  }
}

/** Convert a bounded, passive SVG into pixels before it reaches the existing image uploader. */
export async function prepareShareImage(file: File): Promise<{ file: File; width: number; height: number }> {
  if (!file.size || file.size > 5 * 1024 * 1024) throw new Error('素材文件须小于 5 MB')
  let blob: Blob = file
  const svg = file.type === 'image/svg+xml' || /\.svg$/i.test(file.name)
  if (svg) {
    const source = await file.text()
    if (source.length > 500000 || /<!DOCTYPE|<!ENTITY/i.test(source)) throw new Error('SVG 过大或包含不安全声明')
    const doc = new DOMParser().parseFromString(source, 'image/svg+xml'), root = doc.documentElement
    const tags = ['svg', 'g', 'defs', 'path', 'rect', 'circle', 'ellipse', 'line', 'polygon', 'polyline', 'linearGradient', 'radialGradient', 'stop', 'clipPath', 'title', 'desc']
    const attrs = ['xmlns', 'id', 'viewBox', 'width', 'height', 'x', 'y', 'x1', 'y1', 'x2', 'y2', 'cx', 'cy', 'r', 'rx', 'ry', 'd', 'points', 'transform', 'fill', 'fill-opacity', 'fill-rule', 'stroke', 'stroke-width', 'stroke-opacity', 'stroke-linecap', 'stroke-linejoin', 'stroke-dasharray', 'stroke-dashoffset', 'opacity', 'clip-path', 'clip-rule', 'offset', 'stop-color', 'stop-opacity', 'gradientUnits', 'gradientTransform', 'spreadMethod', 'preserveAspectRatio']
    const nodes = [root, ...Array.from(root.querySelectorAll('*'))]
    if (root.localName !== 'svg' || doc.querySelector('parsererror') || nodes.length > 2000) throw new Error('SVG 格式无效或过于复杂')
    for (const node of nodes) {
      if (node.namespaceURI !== 'http://www.w3.org/2000/svg' || !tags.includes(node.localName)) throw new Error('SVG 仅支持静态形状，不允许脚本、嵌入图片或外部资源')
      for (const attr of Array.from(node.attributes)) {
        if (attr.name === 'xmlns' && attr.value === 'http://www.w3.org/2000/svg') continue
        if (!attrs.includes(attr.name) || /[<>]|javascript:|data:|https?:|\/\//i.test(attr.value)
          || (/url\s*\(/i.test(attr.value) && !/^url\(#[a-zA-Z0-9_-]+\)$/i.test(attr.value))) throw new Error('SVG 包含不安全属性，请使用静态矢量素材')
      }
    }
    const viewBox = root.getAttribute('viewBox')?.trim().split(/[\s,]+/).map(Number)
    const dimension = (name: string, index: number) => Number(root.getAttribute(name)?.replace(/px$/, '')) || viewBox?.[index] || 512
    const width = dimension('width', 2), height = dimension('height', 3)
    if (![width, height].every(value => Number.isFinite(value) && value >= 1 && value <= 2160)) throw new Error('SVG 尺寸须在 1–2160 像素之间')
    root.setAttribute('width', String(width)); root.setAttribute('height', String(height))
    blob = new Blob([new XMLSerializer().serializeToString(root)], { type: 'image/svg+xml' })
  } else if (!imageTypes.includes(file.type)) throw new Error('请选择 PNG、JPG、GIF、WebP 或静态 SVG')
  const url = URL.createObjectURL(blob)
  try {
    const image = new Image(); image.src = url; await image.decode()
    if (!image.naturalWidth || !image.naturalHeight || image.naturalWidth * image.naturalHeight > 20000000) throw new Error('素材图片尺寸过大或无效')
    if (!svg && file.type !== 'image/webp') return { file, width: image.naturalWidth, height: image.naturalHeight }
    const canvas = document.createElement('canvas'); canvas.width = image.naturalWidth; canvas.height = image.naturalHeight
    canvas.getContext('2d')!.drawImage(image, 0, 0)
    const png = await new Promise<Blob>((resolve, reject) => canvas.toBlob(value => value ? resolve(value) : reject(new Error('素材转换失败')), 'image/png'))
    if (png.size > 5 * 1024 * 1024) throw new Error('转换后的素材图片须小于 5 MB')
    return { file: new File([png], file.name.replace(/\.(svg|webp)$/i, '') + '.png', { type: 'image/png' }), width: canvas.width, height: canvas.height }
  } finally { URL.revokeObjectURL(url) }
}
