# ZCode design study — P0 (tokens · icons · motion)

Source: `%LOCALAPPDATA%\Programs\ZCode\resources\app.asar` v3.17.2 (Electron,
React 19 + Tailwind v4). Extracted 2026-11-13.
Artifacts: `styles.css` (480 KB), `prop-map.json` (1670 props × selectors),
`keyframes.json` (59), `TOKENS_DARK.md` (202 dark tokens), `KEYFRAMES.md`,
`SCALE.txt`, `icons/` (19 Android vector drawables + `icons.json`).

## 1. Palette — what we take

ZCode ships two themes: generic neutral (`.dark`) and branded (`.theme-zai-*`).
We use the neutral dark scale with OUR accent (no sky-blue copy).

| role | ZCode dark (`.dark`) | our value |
|---|---|---|
| background | neutral-900 `#171717` | `#161616` (already in `values-night/zyvo_colors.xml`) |
| card / raised | neutral-800 `#262626` | `#202020` surface, `#2B2B2B` card |
| foreground | neutral-200 `#E5E5E5` | `#D4D4D4` (softer, better on phone LCD) |
| foreground subtle | 60 % of fg | `#99D4D4D4` |
| foreground subtlest | 40 % | `#66D4D4D4` |
| border | `#FAFAFA1A` (10 % white) | `#1AFFFFFF` |
| border hover | `#FAFAFA4D` (30 %) | `#26FFFFFF` |
| primary (buttons) | neutral-50 `#FAFAFA` on dark | `#F8F8F8`, text `#000` |
| accent / brand | sky-400 `#38BDF8` → sky-500 `#0EA5E9` | **ours**: `#4ADE80`-family or current accent — decide in P2 |
| destructive | red-500 `#EF4444` | `#FF5C5C` |
| diff added / removed | green-500 / red-500 | `#34D399` / `#FF5C5C` |
| user bubble | 8 % white | `#14FFFFFF` |
| code bg | 5 % white | `#0DFFFFFF` |

Semantic node colors (used in their tool-call timeline, worth copying as
*concept*): file node = sky 18 % tint + sky-300 icon; command node = slate 16 %
tint + slate-300; both have `hover` at +6 % alpha. Our version: one tint family
per tool class (read=blue, write=amber, bash=slate, search=teal, todo=green).

## 2. Scale

- `--ui-font-size: 14px` — everything else is relative: 2xs −5, xs −4, sm −2,
  caption −1, base, lg +2, xl +4, settings-title +10, settings-title-lg +16.
  On Android: base 14sp in lists/cards, 15–16sp for chat prose, 12sp meta.
- Line-height: tight 1.25, snug 1.375, normal 1.5, relaxed 1.625.
- Radius: xs 2px, sm 4px, md 6px, lg 8px, xl 12px, 2xl 16px, 3xl 24px.
  ZCode's composer card = **16px** (2xl); tool cards = 12px (xl); chips = 8px.
- Shadows: sm `0 1px 3px #0000001a`, md `0 4px 6px -1px`, lg `0 10px 15px -3px`.
  In dark UI shadows barely read — they rely on **1px border + surface step**.
- Spacing unit 4px (Tailwind). Card padding 12–16px, row gap 8–12px.
- Easing: `--ease-out: cubic-bezier(0,0,.2,1)` (default for entrances),
  `--ease-in-out: cubic-bezier(.4,0,.2,1)` (loops).
- Fonts: system sans; mono stack = ui-monospace/SFMono/Consolas.

## 3. Motion — the feel, not the frames

ZCode's stream system (what the user liked):
- `zcode-stream-text-in`: `0%{opacity:0} to{opacity:1}` — each incoming chunk
  fades in over ~0.9 s with expo-out; no slide, no blur → text "materialises".
- `zcode-draft-prompt-waterfall` `.26s cubic-bezier(.22,1,.36,1)` — draft line
  enters with `clip-path` reveal + `translateY(-12px)`.
- `tool-call-progress-indeterminate` `1.2s ease-in-out infinite` — a 33 %-wide
  bar sweeping left −33 % → 100 % under the running tool card.
- `zcode-collapsible-up` / `-fade-out` — thinking/tool body collapses by height
  with opacity fade, not a jump.
- `zcode-loading-shimmer` — skeleton gradient sweep for pending blocks.
- `onboarding-logo-border-sweep` — `rotate(360deg)` conic border for the logo.
- 26 `wf-*` mascot keyframes — NOT ported (mascot is our own, later).

**Our motion language (P3, own brand — inspired, not copied):**
1. *Stream*: reveal word-by-word (already shipped) + per-word 120 ms opacity
   0.35→1 ramp (no layout shift). Caret = 2px block, 1.1 s blink, disappears on idle.
2. *Tool card enter*: height 0→measured + opacity 0→1, 180 ms ease-out; icon
   fades in 40 ms later.
3. *Running tool*: 2px track, 30 %-wide highlight sweep 1.2 s ease-in-out
   (Android: `ObjectAnimator` on a `GradientDrawable` translationX).
4. *Collapse/expand*: 160 ms ease-out height + chevron rotate 90°.
5. *Working row*: 3-dot braille/pulse already exists; keep 1 s tick.
6. *Shimmer*: only for skeleton rows, max 1.2 s, never infinite on idle screens.
7. Reduce-motion: `Settings.Global.ANIMATOR_DURATION_SCALE == 0` → skip all
   except opacity fades ≤ 120 ms.

## 4. Icons (lucide, ISC — safe to ship)

Generated into `icons/` as Android vectors (24×24 viewport, stroke 2, round
caps, white stroke → tint at runtime). Mapping to our tool names:

| our tool | icon | file |
|---|---|---|
| thinking / reasoning | `brain` (+ `brain-circuit` for long) | `ic_brain.xml` |
| read / open file | `file-text` | `ic_file_text.xml` |
| write / edit file | `pen-line` (+ `file-pen`) | `ic_pen_line.xml`, `ic_file_pen.xml` |
| bash / shell | `terminal` (+ `square-terminal`) | `ic_terminal.xml` |
| grep | `search` | `ic_search.xml` |
| glob | `folder-search` | `ic_folder_search.xml` |
| todo / plan | `list-checks` | `ic_list_checks.xml` |
| webfetch / websearch | `globe` | `ic_globe.xml` |
| task / subagent | `bot` | `ic_bot.xml` |
| skill / tool | `wrench` | `ic_wrench.xml` |
| diff | `file-diff` | `ic_file_diff.xml` |
| done / error | `circle-check` / `circle-x` | `ic_circle_check.xml`, `ic_circle_x.xml` |
| spinner | `loader-circle` | `ic_loader_circle.xml` |
| sparkle (AI accent) | `sparkles` | `ic_sparkles.xml` |

## 5. Home screen anatomy (screenshot `zcode-1.png`)

- Left rail 240 px: logo mark (rounded square, white Z), back/forward chevrons,
  green "Update" pill; nav rows 36 px with 16 px icons + 14 px labels + shortcut
  hint right-aligned in 11 px grey; section label "Task list" 12 px uppercase-ish
  with search + filter icon buttons; task rows = title (14 px) + relative age
  (12 px, right); footer avatar 24 px + name + 2 icon buttons.
- Main canvas: faint oversized logo watermark (opacity ~3 %) behind content,
  greeting H1 ~30 px semibold, then composer card: 16 px radius, 1 px border,
  `Select project` chip row, textarea placeholder 15 px grey, bottom row
  `+` button (32 px circle) left, `Manage models` text button + 32 px circular
  send button right.
- Title bar is custom (frameless): help, panel, sidebar toggle, min, max, close.
- Background `#161616`; sidebar slightly lighter `#1C1C1C`; card `#262626`;
  borders `#FFFFFF1A`.

## 6. Not copying

- Their mascot (`wf-*`), their exact keyframe names, their sky-blue brand,
  their desktop title bar, their command-palette, their onboarding.
