---
name: Suraksha Nav
colors:
  surface: '#0e141b'
  surface-dim: '#0e141b'
  surface-bright: '#343a42'
  surface-container-lowest: '#090f16'
  surface-container-low: '#161c23'
  surface-container: '#1a2027'
  surface-container-high: '#242a32'
  surface-container-highest: '#2f353d'
  on-surface: '#dde3ed'
  on-surface-variant: '#c2c7cc'
  inverse-surface: '#dde3ed'
  inverse-on-surface: '#2b3139'
  outline: '#8c9196'
  outline-variant: '#42474c'
  surface-tint: '#aacbe2'
  primary: '#aacbe2'
  on-primary: '#113446'
  primary-container: '#8fafc5'
  on-primary-container: '#224355'
  inverse-primary: '#436276'
  secondary: '#b9c8d8'
  on-secondary: '#24323e'
  secondary-container: '#3a4855'
  on-secondary-container: '#a8b7c6'
  tertiary: '#ecbe94'
  on-tertiary: '#462a0b'
  tertiary-container: '#cea27a'
  on-tertiary-container: '#573819'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#c6e7fe'
  primary-fixed-dim: '#aacbe2'
  on-primary-fixed: '#001e2d'
  on-primary-fixed-variant: '#2b4a5d'
  secondary-fixed: '#d5e4f4'
  secondary-fixed-dim: '#b9c8d8'
  on-secondary-fixed: '#0e1d28'
  on-secondary-fixed-variant: '#3a4855'
  tertiary-fixed: '#ffdcbf'
  tertiary-fixed-dim: '#ecbe94'
  on-tertiary-fixed: '#2d1600'
  on-tertiary-fixed-variant: '#604020'
  background: '#0e141b'
  on-background: '#dde3ed'
  surface-variant: '#2f353d'
typography:
  headline-lg:
    fontFamily: JetBrains Mono
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: JetBrains Mono
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-sm:
    fontFamily: JetBrains Mono
    fontSize: 18px
    fontWeight: '500'
    lineHeight: 24px
  body-lg:
    fontFamily: Inter
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  telemetry-num:
    fontFamily: JetBrains Mono
    fontSize: 14px
    fontWeight: '700'
    lineHeight: 18px
    letterSpacing: 0.05em
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.08em
  headline-lg-mobile:
    fontFamily: JetBrains Mono
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 30px
spacing:
  gutter-xs: 4px
  gutter-sm: 8px
  gutter-md: 16px
  gutter-lg: 24px
  gutter-xl: 32px
  margin-screen: 20px
  panel-padding: 16px
---

## Brand & Style

This design system establishes a high-precision technical telemetry environment tailored for complex monitoring, data streaming, and navigational tracking. The personality is strictly utilitarian, calm, and uncompromisingly functional, evoking mission control operations. 

We embrace a **Minimalist / Technical** design style characterized by dense information architecture, strict grid alignment, crisp low-contrast outlines, and zero decorative fluff. Every pixel serves the conveyance of telemetry data.

## Colors

The palette is engineered for high-contrast dark-mode operations, minimizing eye strain during extended monitoring sessions while maintaining immediate legibility of critical status signals.

- **Primary Accent:** Dusty Blue (`#8FAFC5`) used for interactive elements, active states, and key navigational focal points.
- **Secondary / Surface:** Dark Slate (`#22303C`) for card containers, panel borders, and structured component grouping.
- **Background:** Deep Abyssal Slate (`#0D131A`) providing the immersive dark canvas.
- **Status/Signal Colors:** Muted amber (`#D97706`), tactical emerald (`#059669`), and alert crimson (`#DC2626`) for telemetry indicators.

## Typography

Typography pairs clean, highly readable sans-serif interface text with rigorous monospace numbers for telemetry readouts. Monospace numerical alignment ensures critical data streams (speed, altitude, coordinates, latency) never jitter during real-time updates.

## Layout & Spacing

The layout relies on a dense, information-dense **fixed and fluid hybrid grid** optimized for multi-panel dashboards. Spacing follows a strict 4px/8px mathematical rhythm to align telemetry cards, readouts, and charts precisely.

Breakpoints are calibrated for command center displays, tablets, and ruggedized field terminals. Margins and gutters compress dynamically on smaller viewports to maximize data-to-chrome ratios.

## Elevation & Depth

Depth is established via **low-contrast outlines** and subtle surface tonal shifts rather than drop shadows. 
- Containers utilize Dark Slate (`#22303C`) stacked against the Abyssal background (`#0D131A`).
- Borders use crisp, hairline 1px strokes in a muted tone (`#334155`) to demarcate modular telemetry widgets cleanly without visual noise.

## Shapes

The design system employs **sharp (0px roundedness)** corners across all primary containers, buttons, cards, and input fields. This uncompromising geometry reinforces the precision, industrial, and utilitarian nature of telemetry operations.

## Components

- **Buttons:** Sharp-edged, high-contrast hit targets. Primary actions utilize Dusty Blue (`#8FAFC5`) backgrounds with dark text; secondary actions feature transparent fills with 1px slate borders.
- **Chips & Tags:** Compact, uppercase monospace status tags with subtle background fills indicating operational states (nominal, warning, critical).
- **Lists:** Dense tabular rows with alternating subtle background striping for scanning large logs of incoming telemetry data.
- **Checkboxes & Radio Buttons:** Square, precision-drawn geometric selectors with high-contrast active indicator states.
- **Input Fields:** Flush, border-defined text inputs featuring monospace text formatting for coordinate entry or filter parameters.
- **Cards:** Modular data panels enclosed in 1px slate borders, featuring pinned header rows with telemetry stream identifiers.
- **Telemetry Readouts:** Specialized metric blocks pairing a small uppercase label with an oversized, tabular-aligned JetBrains Mono number and real-time delta indicator.