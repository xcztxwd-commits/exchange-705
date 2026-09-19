# P&L share template collection — 2026-09-19

The collection now contains 16 templates. Six were added: Obsidian launch, Aurora flow, Blue circuit, FX trade ticket, Professional review, and Kyoto dusk. The prior ten remain selectable. Project branding replaces exchange branding; financial values are always supplied by the settled order.

## Research and interpretation

- [Bybit: Trade Summary Card](https://www.bybit.com/en/help-center/article/How-to-Generate-your-Trade-Summary-Card): large result typography, themed illustration, editable presentation. Informs the launch composition.
- [BingX official PNL-sharing promotion](https://x.com/BingXEnglish/status/1628668288552271872): blue campaign graphics and compact results. Informs the blue circuit theme; the racetrack artwork is original.
- [cTrader: Sharing deals](https://help.ctrader.com/ctrader-mobile-ios/sharing-deals-ios/): symbol, deal results, genuine market chart with trade markers, QR and save/share. Informs the trade ticket and journal information hierarchy. No unavailable pips or duration metrics are fabricated.
- [Bitget official promotion](https://www.bitget.com/support/articles/12560603841377): teal/black campaign artwork and dimensional forms. This is promotional visual inspiration, not evidence of a particular P&L-card interface.
- User-supplied references inform the existing gold, white and terminal layouts and the city-photography direction. New artwork is interpreted rather than pixel-identical.

## Implementation

No new dependency. Both frontends use the same Canvas renderer and modal. Templates change typography, layout, background and headline together. Export is 1080 × 1440 for the six additions. The three earlier reference templates retain their taller proportions.

The only controls are QR, return percentage and amount. Mobile supports horizontal swipe; the modal stays inside the viewport with a blurred backdrop. Native file sharing is used when supported; otherwise the image downloads. Native system share-sheet behavior still depends on the browser/device.

The journal and existing chart templates use historical candles, with no generated substitute if history is missing. Background files load locally and offer retry on failure. Photos and abstract art are decorative, not market data.

## Original generated assets

Tool: built-in `image_gen`, new-image mode, no reference image inputs. Four backgrounds were generated and inspected; all text and financial data are drawn separately in Canvas. Receipt and journal backgrounds are drawn directly in Canvas.

Saved in both:
- `exchange-frontend/public/share-templates/{launch,aurora,racing,voyage}.png`
- `exchange-pc/public/share-templates/{launch,aurora,racing,voyage}.png`

### launch.png

Premium 3D financial trading share-poster background, portrait 3:4. Deep charcoal almost black background (#111316), faint technical perspective grid in lower third. One elegant brushed dark titanium and warm champagne-gold rocket at bottom-right, approximately x67%-91% y65%-85%, angled slightly up-right, a short soft amber exhaust plume towards lower-left. Restrained cinematic studio rendering with realistic metallic reflections, thin gold rim light and subtle fine dust. The top 60% and left 60% must remain very dark quiet empty negative space for large financial typography. Editorial luxury, mature sophisticated materials, not cartoonish, no coins, no text, no logo, no numbers, no UI, no financial charts, no borders. Full bleed, sharp detailed render.

### aurora.png

Premium abstract 3D trading card background, portrait 3:4. Deep petrol teal-black base, luminous glass ribbon twisting as an elegant torus/knot in the upper-right quadrant only (x58%-98%, y10%-38%). Iridescent cyan and subtle violet edges, realistic frosted glass and smooth studio lighting. Delicate cyan atmospheric light falls down the right edge; bottom and entire left half remain very dark almost black and calm for overlaying order data. Fine luxury-tech editorial surface, sculptural not cartoon, no text, no typography, no symbols, no logos, no UI panels, no charts, no watermark. Full bleed.

### racing.png

Photorealistic editorial background for a blue motorsport-inspired trading result poster, portrait 3:4. Midnight navy (#07142b) dominates the top 65% and left half as quiet negative space. On the lower-right half, a sleek empty racetrack curves toward a vanishing point, luminous cobalt-blue track edges and lime-white lane markings, slight long-exposure light traces, subtle stadium architecture and blue floodlights at far right. Cinematic low angle, polished wet tarmac, sophisticated sports campaign aesthetic. No cars, no people, no brand marks, no text, no numerical labels, no trophies, no UI, no chart. Keep lowest 10% dark enough for a footer. Full bleed.

### voyage.png

Photorealistic Kyoto Japan travel-finance editorial poster background, portrait 3:4. At blue hour transitioning to sunset, a finely detailed traditional Japanese pagoda on the far right edge, warm lantern light, distant modern city skyline and hazy mountains, cherry blossoms in the lower-right foreground. Skyline and temple are concentrated in bottom 45% and rightmost 25%. Upper 55% is clean softly graduated dusty blue/peach sky, left upper and middle 65% empty for dark financial typography. Refined cinematic photograph, soft natural evening light, tasteful restrained color, realistic architecture. No text, no numbers, no logos, no people, no UI, no chart, no borders. Full bleed.

## Preview and validation

All 16 actual Canvas exports appear in `reports/pnl-share-gallery/index.html`, with two overview sheets. Preview data and chart candles are QA fixtures, not live account performance.

Builds and unit checks passed. Browser checks cover all 16 templates at 390×844, 320×568, 667×375, and 1440×1050: close/backdrop, no vertical modal scrolling, all three toggles, swipe/keyboard switching, PNG export, native-share invocation and download fallback, missing-history and background-retry states.

Both frontend Docker services were rebuilt and updated on local ports 17050 (desktop) and 17052 (mobile). The same browser checks passed against the deployed builds with mocked API data and no uncaught page errors. The live timezone API, frontend entry pages and all four new image assets returned HTTP 200 on both ports. The backend remained healthy. Results are saved in `reports/pnl-share-gallery/validation.json`. Native sharing was tested through a browser stub, not a physical phone's OS share sheet.
