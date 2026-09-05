---
name: Horizon Line Telemetry
colors:
  surface: '#07151e'
  surface-dim: '#07151e'
  surface-bright: '#2d3b45'
  surface-container-lowest: '#031018'
  surface-container-low: '#0f1d26'
  surface-container: '#13212a'
  surface-container-high: '#1e2c35'
  surface-container-highest: '#293640'
  on-surface: '#d6e4f1'
  on-surface-variant: '#bec8cc'
  inverse-surface: '#d6e4f1'
  inverse-on-surface: '#25323c'
  outline: '#889296'
  outline-variant: '#3f484c'
  surface-tint: '#82d2eb'
  primary: '#82d2eb'
  on-primary: '#003642'
  primary-container: '#4a9cb4'
  on-primary-container: '#002f3a'
  inverse-primary: '#00677d'
  secondary: '#8ed0e8'
  on-secondary: '#003543'
  secondary-container: '#005a6f'
  on-secondary-container: '#8ed0e8'
  tertiary: '#e3c289'
  on-tertiary: '#412d02'
  tertiary-container: '#ab8e5a'
  on-tertiary-container: '#3a2700'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#b2ebff'
  primary-fixed-dim: '#82d2eb'
  on-primary-fixed: '#001f27'
  on-primary-fixed-variant: '#004e5f'
  secondary-fixed: '#b6eaff'
  secondary-fixed-dim: '#8ed0e8'
  on-secondary-fixed: '#001f28'
  on-secondary-fixed-variant: '#004e60'
  tertiary-fixed: '#ffdea6'
  tertiary-fixed-dim: '#e3c289'
  on-tertiary-fixed: '#271900'
  on-tertiary-fixed-variant: '#594316'
  background: '#07151e'
  on-background: '#d6e4f1'
  surface-variant: '#293640'
typography:
  headline-lg:
    fontFamily: JetBrains Mono
    fontSize: 32px
    fontWeight: '700'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: JetBrains Mono
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: JetBrains Mono
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
  body-lg:
    fontFamily: JetBrains Mono
    fontSize: 15px
    fontWeight: '400'
    lineHeight: 22px
  body-md:
    fontFamily: JetBrains Mono
    fontSize: 13px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 16px
  label-lg:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '600'
    lineHeight: 16px
    letterSpacing: 0.05em
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: '600'
    lineHeight: 14px
    letterSpacing: 0.05em
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 9px
    fontWeight: '500'
    lineHeight: 12px
    letterSpacing: 0.08em
spacing:
  unit: 4px
  gutter-sm: 8px
  gutter-md: 16px
  gutter-lg: 24px
  margin-screen: 24px
  panel-padding: 16px
---

## Brand & Style

This design system is engineered for high-precision technical telemetry, oceanographic monitoring, and complex data-dense interfaces. The visual narrative combines the stark legibility of a command-line terminal with refined, atmospheric maritime tones. 

The aesthetic style is a hybrid of **Minimalism** and **High-Contrast Technical**, utilizing sharp geometry, crisp data boundaries, absolute alignment, and zero superfluous decoration. The interface evokes precision, reliability, and calm situational awareness under demanding operational conditions.

## Colors

The palette is derived directly from the Horizon Line reference, anchored by a deep oceanic surface background (`#0B1922`) ensuring absolute contrast for dense data reads. 

- **Primary (`#4A9CB4` - Open Sea):** Used for primary interactive states, active telemetry lines, and focal metrics.
- **Secondary (`#7EC0D8` - Sky Blue):** Applied to secondary data visualizations, active states, and informative highlights.
- **Tertiary (`#D8B880` - Warm Sand):** Reserved for critical warnings, highlighted anomalies, and focal waypoints.
- **Supportive Neutrals (`#8BC0C8` - Sea Haze, `#D8EEF4` - Cloud):** Used for structural borders, muted text labels, and high-contrast primary text elements.

## Typography

Monospace typography is enforced across all hierarchy levels to guarantee alignment in tabular readouts, logs, and telemetry matrices. Character width consistency enables rapid scanning of fluctuating numerical streams.

Font sizes are strictly bounded to prevent layout shifts on dense viewports. Letter spacing is subtly widened on micro labels to enhance legibility at small scales.

## Layout & Spacing

A strict 4px baseline grid governs all layout metrics, organized into a fluid 12-column telemetry grid. Gutters are kept tight (`8px` or `16px`) to maximize data density.

The layout adapts across breakpoints:
- **Desktop (1024px+):** Multi-pane split views, persistent telemetry streams, and floating diagnostic toolbars.
- **Tablet (768px - 1023px):** Collapsible secondary streams, stacked metric cards.
- **Mobile (< 768px):** Single-column stack with tabbed telemetry panels and high-priority alerts pinned to the viewport top.

## Elevation & Depth

Depth is conveyed strictly through low-contrast outlines ("ghost borders") and tonal background shifts rather than drop shadows. 

Surfaces step back from primary interactive layers using subtle shifts in deep oceanic luminance. Borders use 1px solid lines tinted with Sea Haze (`#8BC0C8`) at low opacity, reinforcing the analytical, instrument-panel aesthetic.

## Shapes

The shape language is strictly **Sharp (0px roundedness)**. All containers, buttons, inputs, and indicators feature 90-degree corners to echo traditional hardware instrument clusters, oscilloscopes, and technical drafting tools.

## Components

- **Buttons:** Sharp-cornered rectangles with high-contrast borders. Primary actions utilize solid Open Sea (`#4A9CB4`) backgrounds with Cloud (`#D8EEF4`) text. Secondary actions use transparent fills with Sea Haze outlines.
- **Chips & Tags:** Compact, uppercase monospaced labels encased in 1px borders, used for system states (`ONLINE`, `SYNCING`, `FAULT`).
- **Lists:** High-density rows separated by faint horizontal rules. Hover states invert row luminance for rapid scanning.
- **Checkboxes & Radio Buttons:** Square and diamond-shaped indicators that fill completely with Sky Blue (`#7EC0D8`) upon activation.
- **Input Fields:** Flush rectangular inputs with bottom-border-only or full-box outlines, paired with always-visible monospaced helper text and cursor indicators.
- **Cards (Telemetry Panels):** Modular data containers featuring a top-left anchor tag, clear coordinate headers, and embedded metric readouts.
- **Specialized Components:** Waveform displays, status matrix grids, threshold sliders, and real-time log consoles.