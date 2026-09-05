---
name: Telemetry System
colors:
  surface: '#0c141b'
  surface-dim: '#0c141b'
  surface-bright: '#323a42'
  surface-container-lowest: '#070f16'
  surface-container-low: '#141c24'
  surface-container: '#192028'
  surface-container-high: '#232b32'
  surface-container-highest: '#2e363d'
  on-surface: '#dbe3ed'
  on-surface-variant: '#c2c7cf'
  inverse-surface: '#dbe3ed'
  inverse-on-surface: '#293139'
  outline: '#8c9199'
  outline-variant: '#42474e'
  surface-tint: '#a3caf5'
  primary: '#a3caf5'
  on-primary: '#003355'
  primary-container: '#194569'
  on-primary-container: '#8bb3dc'
  inverse-primary: '#396187'
  secondary: '#bdf4ff'
  on-secondary: '#00363d'
  secondary-container: '#00e3fd'
  on-secondary-container: '#00616d'
  tertiary: '#efbe78'
  on-tertiary: '#442b00'
  tertiary-container: '#5c3c00'
  on-tertiary-container: '#d5a764'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#cfe5ff'
  primary-fixed-dim: '#a3caf5'
  on-primary-fixed: '#001d34'
  on-primary-fixed-variant: '#1f496e'
  secondary-fixed: '#9cf0ff'
  secondary-fixed-dim: '#00daf3'
  on-secondary-fixed: '#001f24'
  on-secondary-fixed-variant: '#004f58'
  tertiary-fixed: '#ffddb1'
  tertiary-fixed-dim: '#efbe78'
  on-tertiary-fixed: '#291800'
  on-tertiary-fixed-variant: '#614003'
  background: '#0c141b'
  on-background: '#dbe3ed'
  surface-variant: '#2e363d'
typography:
  headline-lg:
    fontFamily: JetBrains Mono
    fontSize: 36px
    fontWeight: '700'
    lineHeight: 44px
    letterSpacing: -0.02em
  headline-lg-mobile:
    fontFamily: JetBrains Mono
    fontSize: 24px
    fontWeight: '700'
    lineHeight: 32px
    letterSpacing: -0.01em
  headline-md:
    fontFamily: JetBrains Mono
    fontSize: 28px
    fontWeight: '600'
    lineHeight: 36px
  headline-sm:
    fontFamily: JetBrains Mono
    fontSize: 20px
    fontWeight: '600'
    lineHeight: 28px
  body-lg:
    fontFamily: JetBrains Mono
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 24px
  body-md:
    fontFamily: JetBrains Mono
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
    letterSpacing: 0.05em
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.05em
spacing:
  gutter-sm: 8px
  gutter-md: 16px
  gutter-lg: 24px
  margin-screen: 16px
  baseline: 4px
---

## Brand & Style

This design system is engineered for industrial telemetry, field monitoring, and outdoor data acquisition environments. The brand personality is precision-driven, utilitarian, and mission-critical. The target audience consists of field engineers, system operators, and data analysts who require instant readability under harsh direct sunlight and high-contrast night operations. 

The aesthetic style merges **High-Contrast / Bold** principles with a dense, utilitarian **Developer Tools** framework. Visuals prioritize absolute clarity over decorative flair, utilizing sharp delineations, monospaced data readouts, and an uncompromised dark mode optimized for OLED and high-nit outdoor displays.

## Colors

The palette is built for extreme legibility in high-glare environments. The foundation rests on a deep carbon-black neutral (`#0B131A`) to maximize contrast ratios. Steel Blue (`#194569`) serves as the primary structural and surface accent, grounding containers and secondary panels. A hyper-vibrant cyan (`#00E5FF`) acts as the secondary accent, reserved exclusively for active states, live data streams, and critical telemetry values.

## Typography

The typography relies entirely on **JetBrains Mono** to ensure that numeric telemetry data, code snippets, and system logs maintain strict character alignment. Monospaced rendering prevents layout jitter when live data values fluctuate rapidly. Font weights are polarized between regular (400) for dense data reading and bold (700) for critical status headers to ensure instant scanning outdoors.

## Layout & Spacing

The layout model uses a **Fluid Grid** system anchored on a strict 4px baseline rhythm. Data-dense dashboards scale dynamically across form factors, shifting from single-column vertical stacks on mobile field units to high-density multi-column grids on desktop monitoring stations. Touch targets maintain a minimum dimension of 44x44px regardless of screen size to accommodate field gloves and rugged use.

## Elevation & Depth

Elevation is communicated strictly through **Low-contrast outlines** and sharp surface tonal shifts rather than ambient shadows, which wash out in direct sunlight. Structural containers use solid 1px borders of Steel Blue (`#194569`) against the carbon-black background. Depth is established through stepped surface lightness: base backgrounds are darkest, active panels are mid-tone, and interactive triggers use solid primary fills.

## Shapes

The shape language employs strict **Sharp (0px roundedness)** geometry. Eliminating border radii maximizes usable screen real estate within dense data tables, charts, and telemetry readouts. Sharp angles reinforce the industrial, high-precision aesthetic and prevent visual softening of critical numerical boundaries.

## Components

- **Buttons:** Sharp 0px corners with solid primary Steel Blue backgrounds or high-contrast cyan outlines. Hover and active states invert colors instantly for tactile feedback in bright environments.
- **Chips & Tags:** Compact, border-defined status indicators used for telemetry states (e.g., *ONLINE*, *FAULT*, *STANDBY*). Utilize uppercase JetBrains Mono labels with high-contrast text.
- **Lists:** Dense, divider-separated data rows optimized for rapid scanning. Alternating row shading provides row-to-row tracking without relying on blur effects.
- **Checkboxes & Radio Buttons:** Square hit-targets with high-contrast inner fill states for active selections. Avoid custom glowing effects in favor of solid vector ticks.
- **Input Fields:** Flush rectangular text boxes featuring a 1px Steel Blue border that shifts to bright cyan upon focus. Placeholder text maintains high-contrast opacity for sunlight legibility.
- **Cards:** Flat panel containers with 1px borders, housing discrete telemetry modules, chart vectors, and system logs.
- **Telemetry Charts & Gauges:** High-contrast line graphs and radial gauges utilizing pure cyan strokes against dark grids, optimized for anti-aliased legibility at a distance.