# Grok 1.2.56 UI study → zyvo port

Source: `C:\Users\Admin\Desktop\MouseWithoutBorders\Grok_1.2.56-release.00_APKPure.xapk`
(package `ai.x.grok`, base apk 56.8MB, splits arm64 + en + mdpi, minSdk 32).

## What the app is
- Single activity `ai.x.grok.main.GrokActivity`, **100% Jetpack Compose** (0 layout files).
- Aggressive R8: compose classes renamed (`am/*`, `fn/*`, `n5/*`), only a handful of
  `androidx.compose.*` stubs keep names → structural recovery via decompile is slow;
  design tokens were taken from real screenshots + strings instead.
- Fonts bundled: **Chirp** (X's typeface: light 300 / regular 400 / medium 500 /
  bold 700 / heavy 800), plus Google Sans Flex, Google Sans Code, Plaid Cern.
  Chirp is proprietary → our substitute is **Inter** (SIL OFL).

## The 3 menus (top tabs, not bottom nav)
`Ask` · `Imagine` · `Build` — centered in the top bar, active = white semibold +
a short 16dp white underline 3dp under the label; inactive = muted gray.
Left: circular outlined hamburger (42dp). Right: circular outlined new-chat (pen-square).

## Chat area
- Background: near-black `#080808`–`#0A0A0B`.
- User message: right-aligned bubble `#111113`, radius ~22dp, padding ~16×10dp,
  16sp white.
- Assistant reply: **no bubble** — plain text on the black background, left aligned,
  full width, 16sp, line height ~1.5, near-white `#ECEDEE`.
- Cards (X post embeds): surface `#16181C`, radius 16dp, faint divider lines.

## Composer
- Container `#19191B`, radius 26dp, ~12dp outer margin, inner padding 14dp.
- Placeholder: "Ask anything..." in muted gray (~`#8B8D93`).
- Bottom row: circular "+" (36dp, `#242426`), mode pill ("Auto" + sparkle + chevron),
  right: white pill "Speak" (waveform icon) — send action is a filled white pill.
- Voice-first emphasis: mic/Speak sits on the right where send lives.

## Strings that describe behaviour
`grok_input_ask_anything` = "Ask anything", `grok_input_type_to_build` = "Type to build",
`grok_input_temporary_conversation`, send/stop/voice actions
(`grok_message_action_send/stop/voice`), usage categories Chat/Imagine/Voice.

## Ported into zyvo (apk-36)
| Grok | zyvo |
|---|---|
| Ask / Imagine / Build tabs | **Chat / Terminal / Settings** (top tabs, same underline style) |
| circular hamburger | `btn_sessions` (bg_circle_button) |
| circular new chat | `btn_new` (pen-line icon) |
| user bubble #111113 r22 | `user_bubble` #1B1C20, r22, 16sp |
| assistant plain text | assistant row: no bubble, 16sp, lineSpacing 6dp |
| composer #19191B r26 | `bg_composer`: `card` #16171A, r26 |
| Chirp | **Inter** 400/500/600 (`res/font/inter.xml`) |
| white pill send | white circle 40dp, dark arrow |

Theme: `Theme.Zyvo.Chat` (values/zyvo_styles.xml) — windowBackground/status/nav = `bg`,
fontFamily = Inter, text colors = zyvo tokens. ChatActivity uses it.

## Gotchas
- `android:fontFamily="@font/..."` needs API 26+; on API 21–25 it falls back
  (no crash observed on our floor, but keep in mind).
- Grok APK **cannot be installed on the test phone** (minSdk 32 vs Android 12 = 31),
  so no live screenshots from the device — use Play Store listing shots + code.
- REA (rea-agents 6.7.0) + jadx-headless-mcp 0.7.1 JAR works, but `load_apk` with
  full resources OOMs on this 3.4GB laptop; use `jadx --single-class` (heap 1.5GB,
  `--no-res`) or apktool + smali grep instead.
