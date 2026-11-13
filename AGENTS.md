# Zyvo Code on Termux fork — project memory

Fork of `termux/termux-app` (package `com.termux`) carrying our Chat UI + the
bundled zyvo engine. Repo `zyvo9/zyvo-termux` (public, default branch **master**),
local `C:\Users\Admin\zyvo-termux`.

## Build / ship
- CI: `.github/workflows/zyvo-build.yml`, triggers on push to `master` +
  `workflow_dispatch`. Name filter: "Zyvo Termux Build". Release `apk-<run_number>`
  (arm64-v8a ~100MB + universal).
- Poll: `C:\Users\Admin\AppData\Local\Temp\zyvo\poll_termux.py <sha>`; failed log:
  `fetch_log.py <job-logs-url> out.log`.
- Push: `git fetch <token-url> "+master:refs/remotes/origin/master"` → rebase →
  push `master:master` (NEVER `main` — that branch does not exist upstream).
- Local machine has NO Android SDK — CI is the only compiler. Brace-balance check
  before push: `python -c "s=open(f).read(); print(s.count('{')-s.count('}'))"`.

## Current state (2026-11-13, apk-27 = e692d14)
- Tool rows (apk-26/27): flat, no card background, 11sp; icon 13dp +
  `Terminal/Write/Edit/Read` label + faint `text_disabled` target; `cleanCommand()`
  strips the `export HOME=…;` preamble; diff shows `+N −N` (green/red) from
  `state.diff`, and `+N` live from `state.input.content` while a write streams
  (`ChatItem.liveLines`); bookkeeping writes (AGENTS.md/SKILL.md/vault) set
  `ChatItem.hidden` and render GONE (first part event has no input yet, so the
  hide check runs again once `cmd` is known).
- Thought rows neutral gray (`text_secondary` / `text_disabled`), brain pulse kept.
- Working bar above composer: `Working 12s` → `Worked for 1m 22s`; setBusy(false)
  must NOT force GONE when `workingStartMs > 0` (a tool completing calls
  setBusy(false) before session.idle — the old code hid the finished duration).
- Assistant streaming shows `plainMarkdown()` (single-pass, no regex: strips
  `*`, backticks, heading hashes, `>` quotes, `- ` → `• `); finished text renders
  full Markwon (tables/linkify/strikethrough). Engine prompt now enforces clean
  structure (sections, blank lines, bullets, tables, short lines) — shipped in
  ZYVO-AI `3e6bb4e`, baked into release `android-v1.17.9` (assets refreshed
  13:44Z; the fork CI pulls that tag, so a rebuild picks the new engine).
- adb now: `192.168.1.109:39111` (port changes per wireless-debug toggle).

## Current state (2026-11-13, apk-22 = 98c2e24)
- Dark-first: `ChatActivity.onCreate` forces `MODE_NIGHT_YES` (Zai dark tokens
  in `values-night/zyvo_colors.xml` now always active).
- Tool icons = lucide set copied from ZCode as `res/drawable/ic_lc_*.xml`
  (24×24, stroke 2, tint at runtime); `ChatAdapter.iconFor()` maps tool name →
  glyph (todo → list-checks, glob → folder-search, read → file-text, write/edit
  → pen-line, web → globe, grep → search, task → bot, skill → sparkles).
- Thought rows: braille spinner replaced by `ic_lc_brain` that pulses
  (alpha 0.55↔1, 1.4 s cosine) while streaming, static 0.75 when done.
- Single notification `ZyvoNotif` (channel `zyvo_engine`, id 4101, Awake/Exit);
  Exit = engine + EngineService + terminal stop (full app exit).
- Error surfacing (apk-21): `session.error` + `message.updated.info.error` →
  error card; 40s no-signal banner; 150s → `failTurn()` card with rate-limit hint;
  drawer row **Engine logs** (tail 250 lines, copy). `EngineManager.isReady()`
  now checks `process.isAlive()`; `pumpLog` end flips state to ERROR; watchdog
  restarts a dead engine.
- Known live issue: opencode free lane `FreeUsageLimitError` per public IP. The
  engine retries silently (no event) — the app now shows it. Fix = fresh IP
  (mobile data). Verified with curl on device.

## UI decisions (user-owned, do not change without asking)
- No bottom nav; hamburger drawer (new chat, sessions, terminal, **Engine logs**,
  settings). No Files tab.
- Composer: 56dp/22dp, model name plain text beside send (no box), Build/Plan chip.
- Assistant text word-by-word reveal; Working row stays for the whole turn with
  elapsed seconds; no "Worked for" summary rows; user echo never rendered twice.
- Notification: ONE "Zyvo Code" entry only (Termux must never post its own).

## 2026-11-13 — next big task: ZCode-inspired redesign (user-approved plan)
Source of truth: ZCode desktop v3.17.2 Electron app at
`%LOCALAPPDATA%\Programs\ZCode\resources\app.asar` (374MB; React 19 + Tailwind v4;
main CSS `out/renderer/assets/styles-NK68EN_n.css`, 480KB, 59 keyframes).
- Decisions: **dark theme**, **inspire-only** (NOT a pixel clone, NOT ZCode's own
  animations — the motion system is OUR brand), but two things close: **tool icons**
  (lucide: brain/file-text/pen-line/terminal/search/list-checks/globe) and the
  **text-streaming feel**.
- Hard constraint: keep the app LIGHT — no new gradle deps, no Lottie/Compose/
  WebView; VectorDrawables + Android Animator only; APK delta target < 500KB;
  no idle animation loops; honour reduce-motion.
- Phases: P0 extract tokens/icons/motion → P1 screen spec → P2 dark theme port →
  P3 our own motion system (+ mascot last) → P4 working-system UX (plan/build,
  todo panel, tool cards, diff, stop/queue, context meter) → P5 build+verify.
- Study artifacts live in `design/zcode-study/` (tokens, icons, notes).

## 2026-11-13 — P0 DONE (extraction)
- `design/zcode-study/`: `styles.css`, `prop-map.json`, `keyframes.json`,
  `TOKENS_DARK.md` (202 dark tokens), `KEYFRAMES.md` (59), `SCALE.txt`,
  `NOTES.md` (decisions), `icons/` = 19 lucide vectors + `icons.json`.
- Icons: 24×24 viewport, stroke 2, white stroke → tint at runtime. Mapping in
  NOTES.md §4. Copy into `res/drawable/` as `ic_tool_*` in P2.
- Dark palette ALREADY exists in `values-night/zyvo_colors.xml` (Zai dark) —
  P2 = refine + make it the app default (user wants dark), not build from zero.
- Extractor scripts (PC temp): `extract_zcode.js`, `extract_icons.js`,
  `dump_tokens.js`, `dump_scale.js`; asar reader pattern documented in
  `extract_zcode.js` (header offsets: size@4, jsonLen@12, data base 8+size).
- Screenshot of ZCode home saved at `Temp\zyvo\zcode-1.png`; window capture +
  click helper `shot_zcode.ps1` / `click_shot.ps1` (SetForegroundWindow is
  unreliable — Windows blocks foreground steal; verify before trusting clicks).
- Next: P2 dark default + tool icons, then P3 own motion (NOT ZCode keyframes).

## adb / device
- Wireless debugging screen shows the VPN address (10.8.0.x) — UNREACHABLE from
  the PC. Take the SAME port it shows and use the phone's LAN IP:
  `adb connect 192.168.1.109:<port>` (LAN IP changes; `Test-Connection` each
  candidate). mdns discovery is often empty — don't wait on it.
- Install: `adb -s <ip:port> install -r -d <apk>`; screenshot:
  `shell screencap -p /sdcard/x.png` then `pull`.
