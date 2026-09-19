// Adapted from xcztxwd-commits/tradingWeb (Apache-2.0), revision adaa5ede407a2d1152bcb5acd3f63d6882a1ca39.
// Changes: retain the two custom tools used here, type callbacks, normalize rectangle bounds.
import { registerOverlay, type OverlayTemplate } from 'klinecharts'

let registered = false
export function registerTradingDrawingOverlays() {
  if (registered) return
  registered = true
  const overlays: OverlayTemplate[] = [
    {
      name: 'tradingRectangle', totalStep: 3,
      needDefaultPointFigure: true, needDefaultXAxisFigure: true, needDefaultYAxisFigure: true,
      createPointFigures: ({ coordinates }) => {
        const [start, end] = coordinates
        if (!start || !end) return []
        return [{ type: 'rect', attrs: { x: Math.min(start.x, end.x), y: Math.min(start.y, end.y), width: Math.abs(end.x - start.x), height: Math.abs(end.y - start.y) } }]
      },
    },
    {
      name: 'tradingArrowLine', totalStep: 3,
      needDefaultPointFigure: true, needDefaultXAxisFigure: true, needDefaultYAxisFigure: true,
      createPointFigures: ({ coordinates }) => {
        const [start, end] = coordinates
        if (!start || !end) return []
        const angle = Math.atan2(end.y - start.y, end.x - start.x)
        return [
          { type: 'line', attrs: { coordinates } },
          { type: 'polygon', attrs: { coordinates: [end, ...[-1, 1].map(side => ({ x: end.x - Math.cos(angle + side * Math.PI / 7) * 11, y: end.y - Math.sin(angle + side * Math.PI / 7) * 11 }))] } },
        ]
      },
    },
  ]
  overlays.forEach(overlay => registerOverlay(overlay))
}
