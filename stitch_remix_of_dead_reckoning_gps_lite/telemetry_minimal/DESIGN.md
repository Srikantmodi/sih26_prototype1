---
name: Telemetry Minimal
colors:
  surface: '#fbf9f1'
  surface-dim: '#dcdad2'
  surface-bright: '#fbf9f1'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f6f4ec'
  surface-container: '#f0eee6'
  surface-container-high: '#eae8e0'
  surface-container-highest: '#e4e3db'
  on-surface: '#1b1c17'
  on-surface-variant: '#45474c'
  inverse-surface: '#30312c'
  inverse-on-surface: '#f3f1e9'
  outline: '#76777d'
  outline-variant: '#c6c6cd'
  surface-tint: '#565e71'
  primary: '#000000'
  on-primary: '#ffffff'
  primary-container: '#141b2c'
  on-primary-container: '#7c8498'
  inverse-primary: '#bfc6dc'
  secondary: '#006a61'
  on-secondary: '#ffffff'
  secondary-container: '#86f2e4'
  on-secondary-container: '#006f66'
  tertiary: '#000000'
  on-tertiary: '#ffffff'
  tertiary-container: '#2f1500'
  on-tertiary-container: '#c76c00'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#dbe2f9'
  primary-fixed-dim: '#bfc6dc'
  on-primary-fixed: '#141b2c'
  on-primary-fixed-variant: '#3f4759'
  secondary-fixed: '#89f5e7'
  secondary-fixed-dim: '#6bd8cb'
  on-secondary-fixed: '#00201d'
  on-secondary-fixed-variant: '#005049'
  tertiary-fixed: '#ffdcc3'
  tertiary-fixed-dim: '#ffb77d'
  on-tertiary-fixed: '#2f1500'
  on-tertiary-fixed-variant: '#6e3900'
  background: '#fbf9f1'
  on-background: '#1b1c17'
  surface-variant: '#e4e3db'
typography:
  headline-lg:
    fontFamily: JetBrains Mono
    fontSize: 32px
    fontWeight: '600'
    lineHeight: 40px
  headline-md:
    fontFamily: JetBrains Mono
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
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
  label-md:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '500'
    lineHeight: 16px
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: '500'
    lineHeight: 14px
spacing:
  gutter: 16px
  margin: 24px
  unit-xs: 4px
  unit-sm: 8px
  unit-md: 16px
  unit-lg: 24px
  unit-xl: 32px
---

## Brand & Style

This design system delivers a clean, high-contrast light technical telemetry theme. Built on a warm light beige background (#FBF9F1), it pairs precise dark navy typography and borders with functional, daylight-optimized status accents in green, amber, and teal. The aesthetic blends minimalism with technical utility—prioritizing absolute clarity, scannability, and data density without visual fatigue.

## Colors

The color palette is anchored by a warm light beige background, ensuring optimal legibility in brightly lit environments. Dark navy provides robust structural lines and primary text contrast, while teal and amber serve as functional status indicators alongside critical operational green.

## Typography

Typography pairs the structural precision of JetBrains Mono for telemetry readouts, metrics, and labels with the neutral clarity of Inter for standard body copy. This combination ensures that numerical data aligns perfectly in dense dashboard grids while maintaining high legibility at small scales.

## Layout & Spacing

A structured fluid grid system underpins the layout, utilizing 16px gutters and 24px outer margins on desktop viewports. The spacing rhythm is built on a strict 4px/8px baseline grid to maintain alignment across dense telemetry panels, charts, and control feeds.

## Elevation & Depth

Visual hierarchy relies on low-contrast outlines and crisp structural borders rather than heavy drop shadows. Containers use fine dark navy borders against the warm beige backdrop, creating a flat, high-precision instrument-cluster feel.

## Shapes

The shape language is strictly sharp (0px roundedness) to reinforce the technical, industrial telemetry aesthetic. Panels, inputs, and buttons feature crisp 90-degree corners and definitive borders.

## Components

- **Buttons:** Sharp-cornered, high-contrast actions utilizing dark navy fills with light text, or outlined variants for secondary functions.
- **Chips & Badges:** Monospaced status indicators featuring distinct background tints for green (nominal), amber (warning), and teal (active data streams).
- **Lists:** Dense, divider-separated data rows optimized for rapid scanning of telemetry parameters.
- **Checkboxes & Radios:** Sharp square and rectangular hit targets with high-contrast active states.
- **Input Fields:** Bordered text inputs and parameter adjusters with monospaced values and clear validation states.
- **Cards:** Flat panel containers enclosed in thin dark navy borders, housing grouped metrics and charts.