---
name: Obsidian Whisper
colors:
  surface: '#131315'
  surface-dim: '#131315'
  surface-bright: '#39393b'
  surface-container-lowest: '#0e0e10'
  surface-container-low: '#1c1b1d'
  surface-container: '#201f21'
  surface-container-high: '#2a2a2c'
  surface-container-highest: '#353437'
  on-surface: '#e5e1e4'
  on-surface-variant: '#c5c7c9'
  inverse-surface: '#e5e1e4'
  inverse-on-surface: '#313032'
  outline: '#8f9194'
  outline-variant: '#44474a'
  surface-tint: '#c6c6c8'
  primary: '#ffffff'
  on-primary: '#2f3132'
  primary-container: '#e2e2e4'
  on-primary-container: '#636466'
  inverse-primary: '#5d5e60'
  secondary: '#4edea3'
  on-secondary: '#003824'
  secondary-container: '#00a572'
  on-secondary-container: '#00311f'
  tertiary: '#ffffff'
  on-tertiary: '#68001a'
  tertiary-container: '#ffdada'
  on-tertiary-container: '#ca003b'
  error: '#ffb4ab'
  on-error: '#690005'
  error-container: '#93000a'
  on-error-container: '#ffdad6'
  primary-fixed: '#e2e2e4'
  primary-fixed-dim: '#c6c6c8'
  on-primary-fixed: '#1a1c1d'
  on-primary-fixed-variant: '#454749'
  secondary-fixed: '#6ffbbe'
  secondary-fixed-dim: '#4edea3'
  on-secondary-fixed: '#002113'
  on-secondary-fixed-variant: '#005236'
  tertiary-fixed: '#ffdada'
  tertiary-fixed-dim: '#ffb3b6'
  on-tertiary-fixed: '#40000c'
  on-tertiary-fixed-variant: '#920028'
  background: '#131315'
  on-background: '#e5e1e4'
  surface-variant: '#353437'
typography:
  headline-xl:
    fontFamily: Plus Jakarta Sans
    fontSize: 40px
    fontWeight: '500'
    lineHeight: 48px
    letterSpacing: -0.03em
  headline-xl-mobile:
    fontFamily: Plus Jakarta Sans
    fontSize: 30px
    fontWeight: '500'
    lineHeight: 38px
    letterSpacing: -0.025em
  headline-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 28px
    fontWeight: '500'
    lineHeight: 36px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 22px
    fontWeight: '500'
    lineHeight: 30px
    letterSpacing: -0.015em
  body-lg:
    fontFamily: Plus Jakarta Sans
    fontSize: 17px
    fontWeight: '400'
    lineHeight: 26px
    letterSpacing: -0.01em
  body-md:
    fontFamily: Plus Jakarta Sans
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 22px
    letterSpacing: 0em
  body-sm:
    fontFamily: Plus Jakarta Sans
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 18px
    letterSpacing: 0.01em
  mono-timer:
    fontFamily: JetBrains Mono
    fontSize: 32px
    fontWeight: '400'
    lineHeight: 38px
    letterSpacing: -0.04em
  mono-timer-mobile:
    fontFamily: JetBrains Mono
    fontSize: 26px
    fontWeight: '400'
    lineHeight: 32px
    letterSpacing: -0.03em
  label-caps:
    fontFamily: JetBrains Mono
    fontSize: 10px
    fontWeight: '500'
    lineHeight: 14px
    letterSpacing: 0.12em
  label-sm:
    fontFamily: JetBrains Mono
    fontSize: 11px
    fontWeight: '400'
    lineHeight: 16px
    letterSpacing: 0.02em
rounded:
  sm: 0.5rem
  DEFAULT: 1rem
  md: 1.5rem
  lg: 2rem
  xl: 3rem
  full: 9999px
spacing:
  gutter: 1.5rem
  margin: 2rem
  space-xs: 0.25rem
  space-sm: 0.5rem
  space-md: 1rem
  space-lg: 1.5rem
  space-xl: 2.5rem
---

## Brand & Style

This design system crafts an intimate, tactile audio companion experience centered on stillness, clarity, and intentionality. Built for sound engineers, writers, field researchers, and focused thinkers, the interface prioritizes effortless auditory capture over visual clutter. 

The aesthetic is anchored in an uncompromising convergence of Scandinavian discipline and Nothing/Apple micro-detailing:
- **Atmospheric Obsidian:** Deep, quiet charcoal spaces that eliminate screen glare and reduce cognitive strain during recording and playback sessions.
- **Whisper-Quiet Tactility:** Controls resemble milled physical hardware—satisfying, understated, and weighted with precise micro-interactions rather than flashy animation.
- **Structural Restraint:** Visual noise is stripped away. Information surfaces cleanly without nested cards or decorative container soup. Whitespace acts as an active structural element, allowing audio waveforms, timers, and transcripts to command attention naturally.

## Colors

The palette operates in strict dark mode, anchored by deep obsidian `#0a0a0c` to emulate the non-emissive finish of anodized matte hardware.

- **Primary (`#f4f4f6`):** Pure optical white with a hair of warmth, reserved for high-priority typography, prominent control glyphs, and active dynamic scrubbers.
- **Secondary / Online State (`#10b981`):** A measured, crystalline emerald used sparingly for live telemetry: active mic feeds, isolated states, lossless processing indicators, and hardware connectivity. Never deployed as broad background fills.
- **Tertiary / Recording Hot State (`#e11d48`):** A muted, deep crimson applied exclusively to indicate armed tracks, active audio recording, and critical capture states.
- **Neutral Hierarchy:**
  - Base Obsidian: `#0a0a0c`
  - Elevated Canvas: `#121216`
  - Surface Glass: `rgba(255, 255, 255, 0.03)`
  - Subdued Text / Glyph Tone: `#71717a` (Slate 500)
  - Ghost Hairlines: `rgba(255, 255, 255, 0.06)` to `rgba(255, 255, 255, 0.10)`

## Typography

The typography couples the refined, balanced curves of **Plus Jakarta Sans** with the mechanical exactitude of **JetBrains Mono**. 

- **Display & Content:** Plus Jakarta Sans provides frictionless readability across transcriptions, track names, and notes, leveraging tight negative tracking on headlines to project contemporary precision.
- **Telemetry & Metadata:** JetBrains Mono is designated strictly for timecode displays, decibel meters, sample rate identifiers (e.g., `48kHz / 24-bit`), and micro-labels.
- **Tracking Discipline:** Micro-labels utilize all-caps paired with expanded tracking (`0.12em`) to maintain legibility at 10px without requiring heavy weights or prominent bounding boxes.

## Layout & Spacing

The layout philosophy implements a **fluid column grid with adaptive safe zones**, emphasizing vertical breathing room over dense packing.

- **Desktop (1024px+):** 12-column layout with max-width constrained to `1200px` for library contexts, shrinking down to an ultra-focused single column `580px` canvas when in active record or playback mode.
- **Tablet (768px - 1023px):** 8-column layout with `1.5rem` gutters and relaxed `2rem` outer padding.
- **Mobile (<768px):** 4-column layout; gutter contracts to `1rem` and canvas margin holds at `1.25rem`. Control modules dock with native safe-area insets at the base.
- **Rhythm & Rules:** Nested containment is banned. Sections flow linearly down the canvas separated by `space-xl` gaps or single hair-thin partition rules (`1px` solid `rgba(255, 255, 255, 0.06)`).

## Elevation & Depth

This system avoids dropped shadows and simulated light sources. Depth is achieved via **chromatic atmospheric layering and ghost boundaries**:

- **Ground Tier (Canvas):** Pure `#0a0a0c`, providing an infinite bottomless horizon.
- **Mid Tier (Panels & Overlays):** Surface `#121216` rendered with optional backdrop-filter blur (`24px`) and an ultra-subtle edge trace: `border: 1px solid rgba(255, 255, 255, 0.06)`.
- **Top Tier (Floating Floating Transports / Scrubbers):** `rgba(22, 22, 27, 0.75)` combined with `backdrop-filter: blur(16px)` and an interior specular hairline: `inset 0 1px 0 rgba(255, 255, 255, 0.08)`.
- **State Feedback:** No elevation lift on click or hover. Interactive affordance is communicated through opacity adjustments (`0.6` to `1.0`) and fine color transitions.

## Shapes

The geometry balances generous **curved pill contours** against linear, razor-sharp baseline grids. 

- Interactive controls, status badges, and trigger nodes utilize complete circular or pill geometry (`rounded-full`) to offer soft tactile targets.
- Content viewports, waveform cards, and transcription blocks adopt soft architectural arcs (`rounded-2xl` / `1rem` up to `1.5rem`), eliminating acute angles to convey fluidity and calm.

## Components

### Buttons & Transport Controls
- **Primary Action (Record Trigger):** Centered circular target (`64px` to `80px`). Idle state: transparent core with an ultra-thin perimeter ring (`rgba(255, 255, 255, 0.15)`). Armed/Active state: smooth inner pulse containing an emerald `#10b981` or crimson `#e11d48` concentric node.
- **Ghost Utility Buttons:** Pill-shaped (`height: 36px`, `padding: 0 16px`), background transparent, border `1px solid rgba(255, 255, 255, 0.08)`, text `#f4f4f6`, active state drops to `rgba(255, 255, 255, 0.05)`.

### Audio Waveforms & Meters
- **Scrubber Canvas:** Custom monolithic track. Played duration rendered in `#f4f4f6`; unplayed tail rendered in `rgba(255, 255, 255, 0.15)`.
- **Level Indicators:** Vertical micro-bars (`2px` width, `2px` spacing). Active decibel headroom switches from slate to subtle `#10b981` at peak clarity.

### Inputs & Transcription Fields
- **Search & Name Fields:** Frameless lines or borderless pills. Inactive shows `rgba(255, 255, 255, 0.35)` placeholder. Focus state illuminates a quiet hairline under-stroke (`1px solid rgba(255, 255, 255, 0.25)`).
- **Text Selection:** Highlighted text tinted with `rgba(16, 185, 129, 0.15)` and text maintained in `#f4f4f6`.

### Lists & Track Items
- Edge-to-edge row architecture with zero container elevation.
- Items are bounded by `border-b border-white/5`.
- Left-aligned mono index/timestamp, center title in medium weight, right-aligned status and length. Hover state reveals a soft surface fill `rgba(255, 255, 255, 0.02)`.

### Checkboxes & Toggle Switches
- **Toggles:** Minimal capsule switch (`36px x 20px`), background `rgba(255, 255, 255, 0.1)`. Thumb is an edge-to-edge inset circle (`16px`) in off-white `#f4f4f6`. Active track fills with `#10b981`.
- **Radio Nodes:** Concentric hollow dots. Active displays a 4px solid centered pip without drop shadows.