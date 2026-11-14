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

## Current state (2026-11-14, apk-32 = 3bae398)
- Context meter (P4 item 1): `ChatFragment.ctxTokens` = newest assistant
  `info.tokens` (input + output + cache.read, `tokensOf()`); strip shows
  `Working 12s · 8.2k ctx` / `Worked for 1m 22s · 12.4k ctx` (`ctxSuffix()`,
  `fmtTokens()`); replayed on session open from `api.messages()`.
- Queue (P4 item 2): busy + typed text → send button becomes arrow and QUEUES
  (`queuedMessage` + `queued_bar` chip above working strip, × cancels);
  busy + empty input → stop/abort as before (`updateSendIcon()`); on
  `session.idle` → `sendQueuedIfAny()` re-sends it. Queue cleared on
  new/open session.
- Gotcha: zyvo_strings.xml already had `cancel` → duplicate resource breaks
  `mergeDebugResources`; new string is `queued_cancel_desc`.
- Device support: minSdk 21, targetSdk 28, compileSdk 36; splits list all ABIs
  but the engine `libzyvo.so` ships arm64-v8a only → effectively 64-bit ARM
  Android phones.
- Wireless adb (2026-11-14): phone shows up as mDNS
  `adb-Z60plusBD2310070295-7kxTqf._adb-tls-connect._tcp` (user-supplied IP:port
  changes per toggle; the mDNS serial works without it).

## Earlier state (2026-11-13, apk-30 = a85bbc0)- Motion (P3, our own, reduce-motion aware): rows fade+rise 6dp/170ms once
  (`ChatItem.animated`, `ChatAdapter.animateIn`); running tool rows get a
  brand-blue sweep (`bg_sweep.xml` + `ToolVH.startSweep/stopSweep`, 1.3s,
  cancelled on recycle); `animateLayoutChanges` on tool/thought/todo roots;
  working bar fades in. `reduceMotion` = Settings.Global.ANIMATOR_DURATION_SCALE == 0.
- Todo card (P4): engine `todowrite` parts never render as tool rows — one
  `ChatItem.TODO` card ("Tasks n/m") is updated in place (`applyTodos`), rows
  ✓/◐/○/✕, completed = dim + strikethrough, `item_todo.xml` + `TodoVH`.
  Live: a first event with empty input may create a ghost row → hidden then
  (`existing.hidden = true`). Replay: `parseMessages` merges all todowrite
  parts into one card. Engine also keeps a `todo` table in opencode.db
  (`session_id, content, status, priority, position`).
- Gotcha: engine tool part `state.title` for todowrite is "N todos", and
  `titleFor()` matched "write" → ghost rows titled "Write"; now todo is matched
  first ("Tasks").
- DB inspect recipe: `su -c cp .../share/zyvo/opencode.db(-wal,-shm) /sdcard/`
  → adb pull → sqlite3 (`part.data` JSON: `type`,`tool`,`state.input/status/diff`).

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

## 2026-11-14 (later) — Grok/ChatGPT-class UI port (apk-36/37)
- Source study: Grok 1.2.56 xapk (ai.x.grok, minSdk 32 -> CANNOT install on the
  Android-12 phone, so no live shots). 100% Compose, R8-renamed; tokens taken from
  Play Store screenshots + strings. Full write-up: `design/grok-study/NOTES.md`.
- Grok's 3 menus = top tabs **Ask / Imagine / Build** (white active label + 16dp
  underline), circular hamburger left, circular new-chat right, pure-black bg,
  user bubble #111113 r22, **assistant = plain text, no bubble**, composer
  #19191B r26 with + / mode pill / white send pill. Font = Chirp (proprietary)
  -> we bundle **Inter 400/500/600** (`res/font/inter.xml`).
- Ported: top tabs **Chat / Terminal / Settings** (Terminal/Settings open the
  Termux activities), new tokens in `values-night/zyvo_colors.xml` (bg #0A0A0B,
  card #16171A, text #ECEDEE / #9A9CA3), `Theme.Zyvo.Chat` in
  `values/zyvo_styles.xml` (Inter + bg window), user bubble r22 16sp,
  assistant 16sp/lineSpacing 6dp, composer r26.
- Gotcha: removing a view from a layout breaks `R.id` in Java at compile time —
  after the top-bar rewrite, `title`/`top_engine_dot`/`model_chip` lookups
  became null assignments (build 36 javac failure, fixed in 68efb12).
- PC tooling for this: REA 6.7.0 (`%APPDATA%\npm\rea.cmd`) + jadx-headless-mcp
  0.7.1 JAR at `~/.local/share/rea/android/`; jadx CLI at `C:\Users\Admin\Tools\jadx`;
  apktool 2.11.1 at `C:\Users\Admin\Tools\apktool`. REA `load_apk` with full
  resources OOMs on this 3.4GB laptop -> use `jadx --no-res --single-class <cls>`
  or apktool smali + grep.

## 2026-11-14 (apk-38) — tool cards + real token streaming
- Tool rows are now CARDS for every tool (g_tool: card #16171A + 1dp line,
  r10, padding 9dp). Stage cycle on tap: 1 line -> 40 lines -> all -> 1 line
  (ChatItem.expanded + ull, ChatAdapter.nextStage). Running tools show a
  3-line live TAIL; failed tools 6 lines; meta line "N lines · tap to expand".
- +N −N is a pill (g_diff_pill) that toggles ChatItem.showDiff and renders
  the real diff inline (diffSpans: + green / - red / @@ dim, 300-line cap).
- STREAMING ROOT CAUSE: the engine publishes token-level message.part.delta
  {sessionID, messageID, partID, field:"text", delta} for BOTH reasoning and
  answer text (verified inside the shipped libzyvo.so of apk-37; engine source
  packages/opencode/src/session/processor.ts:398/785). message.part.updated
  only fires at part START (empty text) and END — our client ignored deltas, so
  "Thinking" appeared fully formed together with the first tool call. Now:
  ChatFragment.handlePartDelta -> pplyDelta, partTypes map (pid->type),
  pendingDeltas buffer for deltas that overtake the snapshot, thought rows
  created on the empty reasoning snapshot, dirtyThoughts flushed every 60ms.
- Second smoothness bug: startReveal() used to emoveCallbacks + repost every
  token, so a fast stream postponed the reveal forever (text appeared only when
  the stream paused). Now guarded by evealScheduled (one queued tick at a
  time); same guard for 	houghtScheduled.
- Thought rows now show a live 2-line dim TAIL while streaming (indThought),
  so reasoning is visible as it arrives, not only after expand.
- Verified resource refs with Temp\zyvo\check_refs.py (R.id/string/drawable
  vs res/) before pushing — no Android SDK locally, so this + XML parse is the
  pre-flight.
