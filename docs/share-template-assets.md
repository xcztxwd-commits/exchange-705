# Reference P&L poster assets

Three additional templates use the user's supplied poster references: the first image's left black/gold design, the second image's right white architecture design, and the third image's dark chart design. Existing seven templates are retained.

Backgrounds were reconstructed with the built-in `image_gen` tool, not the CLI. They are reference-based raster reconstructions, not pixel-identical extractions. All order values, cards, charts, labels, and project branding are rendered separately in Canvas. Reference brand logos are not included. Order data is never baked into these assets.

Each frontend has its own copy under `public/share-templates/` because their Docker build contexts are independent:

- `exchange-frontend/public/share-templates/reference-gold.png`
- `exchange-frontend/public/share-templates/reference-white.png`
- `exchange-frontend/public/share-templates/reference-terminal.png`
- `exchange-pc/public/share-templates/reference-gold.png`
- `exchange-pc/public/share-templates/reference-white.png`
- `exchange-pc/public/share-templates/reference-terminal.png`

The gold and white posters export at 1080 × 1982. The dark chart poster exports at 1080 × 1834, retaining the references' proportions. The prior seven posters remain 1080 × 1440. Missing history or background assets produce a retry state; financial charts are never replaced with decorative data. Quantity, capital and fees are hidden along with the amount toggle. Order ID and timestamps are included in these three detail-oriented layouts.

## Final prompts

### Gold background

Edit / background extraction for a real web application's dynamic trade poster. From the supplied image select ONLY the LEFT black-and-gold portrait poster, exclude the right white poster and outside gray margins. Produce one portrait background at the left poster's exact approximately 654:1199 ratio. Preserve its near-black softly textured surface, subtle gold diagonal light on upper right, and especially the photorealistic jagged golden rim-lit mountain ridge running across the bottom 18%, exactly in the reference's locations and subtle brightness. Remove ALL logos, ALL letters/text/numbers, flags, ALL candlesticks/chart/grid/volume bars, panels, outlines, badges, separators, financial widgets and chart arrows. Fill removed content with matching near-black underlying textured background. Final image is background art ONLY with no text, no symbols, no UI boxes. Keep broad calm dark negative space in the top 80% for overlaying dynamic data. Keep bottom mountain photographic detail, do NOT replace mountains with flat geometric triangles. Full bleed rectangular image, no rounded outer corners, no border.

### White background

Precise edit / extract clean background for an application's dynamic poster. Use ONLY the RIGHT WHITE POSTER from the reference; exclude the entire left black poster. Output single full bleed portrait at 654:1200 aspect. Preserve the right poster's off-white lightly textured airy surface and the photorealistic minimalist pale concrete architectural blocks near upper-right (occupying x42%-100%, y10%-32%), with exactly the original soft lighting, fine concrete gradients, building arrangement and bright lime diagonal light streak. Keep the original lettering painted on the building: 'TRADE SMARTER LIVE BRIGHTER' in thin gray perspective type in exactly the original position. Remove ALL OTHER text, logos, currency symbols, numbers, all cards/UI panels/lines/arrows/QR codes and candlestick charts. Clear these to the same plain very subtle off-white paper background. The rest of the image should be calm near-white negative space for dynamic data overlaid later. No new objects. No flat vector illustration substitute. No outside frame, no rounded corner crop. Do not move or enlarge the architecture relative to the reference.

### Dark chart background

Precise edit / background extraction. Reproduce this single portrait trade poster BACKGROUND ONLY with the same 285:484 aspect ratio and same composition. Preserve dark nearly black navy background and the small realistic silver-gray Earth globe at bottom-right, occupying x78%-95%, y84%-96%, showing Africa and Europe with glossy ocean and light silver land. Remove every logo, word, number, chart line, candlestick, grid, data label, frame, box and border. Fill all cleared areas with smooth matching navy-black (#020b12 approximate). Keep the Earth unchanged in location, shape, scale, surface and lighting; keep the other 95% clean empty background. No letters, no symbols, no UI elements. Full-bleed rectangular portrait image. This is an application background asset onto which real order data will be drawn.
