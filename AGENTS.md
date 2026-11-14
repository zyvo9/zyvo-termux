# Zyvo Code on Termux fork â€” project memory

Fork of `termux/termux-app` (package `com.termux`) carrying our Chat UI + the
bundled zyvo engine. Repo `zyvo9/zyvo-termux` (public, default branch **master**),
local `C:\Users\Admin\zyvo-termux`.

## Build / ship
- CI: `.github/workflows/zyvo-build.yml`, triggers on push to `master` +
  `workflow_dispatch`. Name filter: "Zyvo Termux Build". Release `apk-<run_number>`
  (arm64-v8a ~100MB + universal).
- Poll: `C:\Users\Admin\AppData\Local\Temp\zyvo\poll_termux.py <sha>`; failed log:
  `fetch_log.py <job-logs-url> out.log`.
- Push: `git fetch <token-url> "+master:refs/remotes/origin/master"` â†’ rebase â†’
  push `master:master` (NEVER `main` â€” that branch does not exist upstream).
- Local machine has NO Android SDK â€” CI is the only compiler. Brace-balance check
  before push: `python -c "s=open(f).read(); print(s.count('{')-s.count('}'))"`.

## Current state (2026-11-14, apk-32 = 3bae398)
- Context meter (P4 item 1): `ChatFragment.ctxTokens` = newest assistant
  `info.tokens` (input + output + cache.read, `tokensOf()`); strip shows
  `Working 12s Â· 8.2k ctx` / `Worked for 1m 22s Â· 12.4k ctx` (`ctxSuffix()`,
  `fmtTokens()`); replayed on session open from `api.messages()`.
- Queue (P4 item 2): busy + typed text â†’ send button becomes arrow and QUEUES
  (`queuedMessage` + `queued_bar` chip above working strip, Ã— cancels);
  busy + empty input â†’ stop/abort as before (`updateSendIcon()`); on
  `session.idle` â†’ `sendQueuedIfAny()` re-sends it. Queue cleared on
  new/open session.
- Gotcha: zyvo_strings.xml already had `cancel` â†’ duplicate resource breaks
  `mergeDebugResources`; new string is `queued_cancel_desc`.
- Device support: minSdk 21, targetSdk 28, compileSdk 36; splits list all ABIs
  but the engine `libzyvo.so` ships arm64-v8a only â†’ effectively 64-bit ARM
  Android phones.
- Wireless adb (2026-11-14): phone shows up as mDNS
  `adb-Z60plusBD2310070295-7kxTqf._adb-tls-connect._tcp` (user-supplied IP:port
  changes per toggle; the mDNS serial works without it).

## Earlier state (2026-11-13, apk-30 = a85bbc0)- Motion (P3, our own, reduce-motion aware): rows fade+rise 6dp/170ms once
  (`ChatItem.animated`, `ChatAdapter.animateIn`); running tool rows get a
  brand-blue sweep (`bg_sweep.xml` + `ToolVH.startSweep/stopSweep`, 1.3s,
  cancelled on recycle); `animateLayoutChanges` on tool/thought/todo roots;
  working bar fades in. `reduceMotion` = Settings.Global.ANIMATOR_DURATION_SCALE == 0.
- Todo card (P4): engine `todowrite` parts never render as tool rows â€” one
  `ChatItem.TODO` card ("Tasks n/m") is updated in place (`applyTodos`), rows
  âœ“/â—/â—‹/âœ•, completed = dim + strikethrough, `item_todo.xml` + `TodoVH`.
  Live: a first event with empty input may create a ghost row â†’ hidden then
  (`existing.hidden = true`). Replay: `parseMessages` merges all todowrite
  parts into one card. Engine also keeps a `todo` table in opencode.db
  (`session_id, content, status, priority, position`).
- Gotcha: engine tool part `state.title` for todowrite is "N todos", and
  `titleFor()` matched "write" â†’ ghost rows titled "Write"; now todo is matched
  first ("Tasks").
- DB inspect recipe: `su -c cp .../share/zyvo/opencode.db(-wal,-shm) /sdcard/`
  â†’ adb pull â†’ sqlite3 (`part.data` JSON: `type`,`tool`,`state.input/status/diff`).

## Current state (2026-11-13, apk-27 = e692d14)
- Tool rows (apk-26/27): flat, no card background, 11sp; icon 13dp +
  `Terminal/Write/Edit/Read` label + faint `text_disabled` target; `cleanCommand()`
  strips the `export HOME=â€¦;` preamble; diff shows `+N âˆ’N` (green/red) from
  `state.diff`, and `+N` live from `state.input.content` while a write streams
  (`ChatItem.liveLines`); bookkeeping writes (AGENTS.md/SKILL.md/vault) set
  `ChatItem.hidden` and render GONE (first part event has no input yet, so the
  hide check runs again once `cmd` is known).
- Thought rows neutral gray (`text_secondary` / `text_disabled`), brain pulse kept.
- Working bar above composer: `Working 12s` â†’ `Worked for 1m 22s`; setBusy(false)
  must NOT force GONE when `workingStartMs > 0` (a tool completing calls
  setBusy(false) before session.idle â€” the old code hid the finished duration).
- Assistant streaming shows `plainMarkdown()` (single-pass, no regex: strips
  `*`, backticks, heading hashes, `>` quotes, `- ` â†’ `â€¢ `); finished text renders
  full Markwon (tables/linkify/strikethrough). Engine prompt now enforces clean
  structure (sections, blank lines, bullets, tables, short lines) â€” shipped in
  ZYVO-AI `3e6bb4e`, baked into release `android-v1.17.9` (assets refreshed
  13:44Z; the fork CI pulls that tag, so a rebuild picks the new engine).
- adb now: `192.168.1.109:39111` (port changes per wireless-debug toggle).

## Current state (2026-11-13, apk-22 = 98c2e24)
- Dark-first: `ChatActivity.onCreate` forces `MODE_NIGHT_YES` (Zai dark tokens
  in `values-night/zyvo_colors.xml` now always active).
- Tool icons = lucide set copied from ZCode as `res/drawable/ic_lc_*.xml`
  (24Ã—24, stroke 2, tint at runtime); `ChatAdapter.iconFor()` maps tool name â†’
  glyph (todo â†’ list-checks, glob â†’ folder-search, read â†’ file-text, write/edit
  â†’ pen-line, web â†’ globe, grep â†’ search, task â†’ bot, skill â†’ sparkles).
- Thought rows: braille spinner replaced by `ic_lc_brain` that pulses
  (alpha 0.55â†”1, 1.4 s cosine) while streaming, static 0.75 when done.
- Single notification `ZyvoNotif` (channel `zyvo_engine`, id 4101, Awake/Exit);
  Exit = engine + EngineService + terminal stop (full app exit).
- Error surfacing (apk-21): `session.error` + `message.updated.info.error` â†’
  error card; 40s no-signal banner; 150s â†’ `failTurn()` card with rate-limit hint;
  drawer row **Engine logs** (tail 250 lines, copy). `EngineManager.isReady()`
  now checks `process.isAlive()`; `pumpLog` end flips state to ERROR; watchdog
  restarts a dead engine.
- Known live issue: opencode free lane `FreeUsageLimitError` per public IP. The
  engine retries silently (no event) â€” the app now shows it. Fix = fresh IP
  (mobile data). Verified with curl on device.

## UI decisions (user-owned, do not change without asking)
- No bottom nav; hamburger drawer (new chat, sessions, terminal, **Engine logs**,
  settings). No Files tab.
- Composer: 56dp/22dp, model name plain text beside send (no box), Build/Plan chip.
- Assistant text word-by-word reveal; Working row stays for the whole turn with
  elapsed seconds; no "Worked for" summary rows; user echo never rendered twice.
- Notification: ONE "Zyvo Code" entry only (Termux must never post its own).

## 2026-11-13 â€” next big task: ZCode-inspired redesign (user-approved plan)
Source of truth: ZCode desktop v3.17.2 Electron app at
`%LOCALAPPDATA%\Programs\ZCode\resources\app.asar` (374MB; React 19 + Tailwind v4;
main CSS `out/renderer/assets/styles-NK68EN_n.css`, 480KB, 59 keyframes).
- Decisions: **dark theme**, **inspire-only** (NOT a pixel clone, NOT ZCode's own
  animations â€” the motion system is OUR brand), but two things close: **tool icons**
  (lucide: brain/file-text/pen-line/terminal/search/list-checks/globe) and the
  **text-streaming feel**.
- Hard constraint: keep the app LIGHT â€” no new gradle deps, no Lottie/Compose/
  WebView; VectorDrawables + Android Animator only; APK delta target < 500KB;
  no idle animation loops; honour reduce-motion.
- Phases: P0 extract tokens/icons/motion â†’ P1 screen spec â†’ P2 dark theme port â†’
  P3 our own motion system (+ mascot last) â†’ P4 working-system UX (plan/build,
  todo panel, tool cards, diff, stop/queue, context meter) â†’ P5 build+verify.
- Study artifacts live in `design/zcode-study/` (tokens, icons, notes).

## 2026-11-13 â€” P0 DONE (extraction)
- `design/zcode-study/`: `styles.css`, `prop-map.json`, `keyframes.json`,
  `TOKENS_DARK.md` (202 dark tokens), `KEYFRAMES.md` (59), `SCALE.txt`,
  `NOTES.md` (decisions), `icons/` = 19 lucide vectors + `icons.json`.
- Icons: 24Ã—24 viewport, stroke 2, white stroke â†’ tint at runtime. Mapping in
  NOTES.md Â§4. Copy into `res/drawable/` as `ic_tool_*` in P2.
- Dark palette ALREADY exists in `values-night/zyvo_colors.xml` (Zai dark) â€”
  P2 = refine + make it the app default (user wants dark), not build from zero.
- Extractor scripts (PC temp): `extract_zcode.js`, `extract_icons.js`,
  `dump_tokens.js`, `dump_scale.js`; asar reader pattern documented in
  `extract_zcode.js` (header offsets: size@4, jsonLen@12, data base 8+size).
- Screenshot of ZCode home saved at `Temp\zyvo\zcode-1.png`; window capture +
  click helper `shot_zcode.ps1` / `click_shot.ps1` (SetForegroundWindow is
  unreliable â€” Windows blocks foreground steal; verify before trusting clicks).
- Next: P2 dark default + tool icons, then P3 own motion (NOT ZCode keyframes).

## adb / device
- Wireless debugging screen shows the VPN address (10.8.0.x) â€” UNREACHABLE from
  the PC. Take the SAME port it shows and use the phone's LAN IP:
  `adb connect 192.168.1.109:<port>` (LAN IP changes; `Test-Connection` each
  candidate). mdns discovery is often empty â€” don't wait on it.
- Install: `adb -s <ip:port> install -r -d <apk>`; screenshot:
  `shell screencap -p /sdcard/x.png` then `pull`.

## 2026-11-14 (later) â€” Grok/ChatGPT-class UI port (apk-36/37)
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
- Gotcha: removing a view from a layout breaks `R.id` in Java at compile time â€”
  after the top-bar rewrite, `title`/`top_engine_dot`/`model_chip` lookups
  became null assignments (build 36 javac failure, fixed in 68efb12).
- PC tooling for this: REA 6.7.0 (`%APPDATA%\npm\rea.cmd`) + jadx-headless-mcp
  0.7.1 JAR at `~/.local/share/rea/android/`; jadx CLI at `C:\Users\Admin\Tools\jadx`;
  apktool 2.11.1 at `C:\Users\Admin\Tools\apktool`. REA `load_apk` with full
  resources OOMs on this 3.4GB laptop -> use `jadx --no-res --single-class <cls>`
  or apktool smali + grep.

## 2026-11-14 (apk-38) â€” tool cards + real token streaming
- Tool rows are now CARDS for every tool (g_tool: card #16171A + 1dp line,
  r10, padding 9dp). Stage cycle on tap: 1 line -> 40 lines -> all -> 1 line
  (ChatItem.expanded + ull, ChatAdapter.nextStage). Running tools show a
  3-line live TAIL; failed tools 6 lines; meta line "N lines Â· tap to expand".
- +N âˆ’N is a pill (g_diff_pill) that toggles ChatItem.showDiff and renders
  the real diff inline (diffSpans: + green / - red / @@ dim, 300-line cap).
- STREAMING ROOT CAUSE: the engine publishes token-level message.part.delta
  {sessionID, messageID, partID, field:"text", delta} for BOTH reasoning and
  answer text (verified inside the shipped libzyvo.so of apk-37; engine source
  packages/opencode/src/session/processor.ts:398/785). message.part.updated
  only fires at part START (empty text) and END â€” our client ignored deltas, so
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
  vs res/) before pushing â€” no Android SDK locally, so this + XML parse is the
  pre-flight.

## 2026-11-14 (apk-40) - UI now mirrors the zyvo CLI itself
- User asked to study the CLI and match it ("zyvo te valo kore dekho"). TUI
  source: packages/tui/src/routes/session/index.tsx - PART_MAPPING = text /
  tool / reasoning; ReasoningPart = 1-line collapsed header + duration;
  ToolPart splits BlockTool (Shell, Write, Edit, ApplyPatch - panel card with
  a left border, command line shown only when open) vs InlineTool (read, grep,
  glob, task, todowrite - flat 1-2 line row, hover, failed = red, denied =
  strikethrough); DiffStats = +N green / -N red; ToolPart.shouldHide hides
  bookkeeping write/edit to AGENTS.md, ZyvoVault, SKILL.md, skills/.
- Palette: packages/tui/src/theme/assets/zyvo.json is the DEFAULT theme
  (config/theme.tsx kv.get("theme","zyvo")). Warm coffee/amber:
  darkStep1 #0e0c09 (bg), step2 #16130e, step3 #1f1b14, step4 #29241b,
  step5 #332d22, step6 #3e3729, step7 #4a4232, step8 #5c5340,
  step9 #e8a33d (primary amber), step10 #ffc27a, step11 #8a8172 (muted),
  step12 #f2ede2 (text), secondary #d9b45a, accent #e8734a, red #e06c75,
  orange #f5a742, green #7fd88f, cyan #56b6c2, yellow #e5c07b.
  Markdown: heading=accent, link=step9, code=green, strong=orange.
- Android: values-night/zyvo_colors.xml rewritten to that palette;
  bg_block.xml = panel r3 (layer-list android:width needs API 23, so the 3dp
  left border is a real View: left_border in item_tool.xml);
  item_tool.xml = tool_card > left_border + tool_inner (head row + cmd_line
  "$ command" + output + expand + diff_body + sweep);
  ChatAdapter.bindTool picks block (shell/write/edit/patch) vs inline,
  bg_sweep is amber now. Build 40 green.
- Local APK delivery without adb: python -m http.server 8485 in
  Temp\zyvo\apkserve + cloudflared quick tunnel ->
  https://racing-nut-license-specialist.trycloudflare.com/zyvo-40.apk
- User preference (2026-11-14): the app's look must follow the zyvo CLI itself (warm amber, block/inline tool rows) - NOT a Grok clone. Grok study was only a layout reference.
- Design split (final, 2026-11-14): SHELL from Grok (top tabs + underline, circular hamburger/new-chat, user bubble r22 / assistant plain text, r26 composer, Inter, empty-state chips, FAB) + CONTENT from the CLI (amber coffee palette, block/inline tool rows, reasoning header, mono code, +N -N). Not Grok's neutral #080808, not Grok's voice-first Speak pill.

## 2026-11-14 (apk-41) - Grok-style motion pass
- Tabs now share ONE underline (tab_row + tab_underline in fragment_chat.xml);
  each tab keeps an 8dp spacer instead of its own 3dp view. ChatFragment
  moveUnderline(tab, animate) centres it under the tapped tab, 220ms decelerate;
  underline colour = @color/accent (amber).
- Empty/home screen entrance: animateEmpty() staggers empty_title, empty_hint,
  chip1..3 with alpha 0->1 + 12dp rise, 50ms apart, 300ms each.
- Thinking shimmer: ThoughtVH.startShimmer() runs a LinearGradient highlight
  across the "Thinking..." title (ValueAnimator 1250ms infinite, Matrix
  translate, dim #8A8172 -> bright #F2EDE2); stopped on completion/recycle.
- Small feedback: send button scales to 0.9 on touch-down; scroll-to-bottom FAB
  pops in with OvershootInterpolator 1.3.
- All of it respects animator_duration_scale via animationsOn().
- Existing motion (kept): fadeIn rows (170ms, 6dp rise), word-by-word reveal
  32ms, braille tool spinner, sweep band on running tool, working-bar fade.
- Build 41 green; APK served at .../zyvo-41.apk.

## 2026-11-14 (apk-42) - streaming perf: why it stuttered, what fixed it
- Symptom: word streaming felt laggy/jumpy, thinking row flickered.
- Causes (all in the app, engine was fine):
  1. revealTick did a FULL notifyItemChanged every 32ms -> whole row rebind:
     plainMarkdown over the whole text, setText (full re-layout), footer,
     action visibility, animateIn path.
  2. step = max(3, backlog/8) at 32ms -> geometric catch-up, text stayed far
     behind the model.
  3. thought flush did a full rebind every 60ms and tail() used split("\n")
     on the entire reasoning text (allocation per bind).
  4. scrollIfPinned ran on every tick (60 scroll calls/s) -> bounce.
  5. reasoning was capped with substring(0,6000) -> the live tail FROZE on
     long thinking; now keeps the last 6000 chars.
- Fix: ChatAdapter.PAYLOAD_STREAM + 3-arg onBindViewHolder -> bindStreamText /
  bindStreamThought touch ONLY the TextView (and hide the footer/actions).
  Reveal: 16ms tick, step = max(2, (backlog+3)/4), word snap only when
  backlog > 32, full bind exactly once when the stream finishes.
  tail(): backward lastIndexOf scan, no array allocation.
  Scroll: scrollIfPinnedThrottled() max once per 60ms while pinned.
- Verification gate used: Temp\zyvo\check_refs.py (braces + R refs) then CI.

## 2026-11-14 (apk-44/45) - Grok-black theme + Codex tool rows + error system
- User decision OVERRIDES the old amber-coffee direction: **full Grok black**
  (1A) - bg #000, text #FFF/#9A9A9A/#6B6B6B, line #262626, card #1A1A1A,
  user bubble #2A2A2A, amber #E8A33D ONLY on the send button; tool rows are
  **flat** Codex-style (2A action row = copy + regenerate + share; 3A one build).
- Silent-quota root cause (fixed 2bd5849, apk-44): engine publishes
  `session.status` retry events every ~2s with NO message part - app now
  renders one live retry card + "Retrying - attempt N" strip.
- apk-45 (2966682): flat tool row = 26dp outlined icon box + gray verb label
  (`labelFor`: Ran command / Writing file / Wrote file / Read file ...) +
  +N -N + bold white title + chevron; tap 10 lines -> full -> closed.
  Thought row = lightbulb + "Thought for 4s >" (shimmer updated to grays).
  Reply action row btn_copy/btn_regen/btn_share; regen re-sends the prompt
  above (`regenerateReply`). Errors: `reportError(name, raw)` dispatches the
  engine error union - MessageAbortedError = footer "interrupted" only,
  ContextOverflow/OutputLength/StructuredOutput/APIError = red-border panel
  (item_error.xml) under the reply, auth/quota = amber notice; `errorName()`
  reads name from the error object (or .data.name).
- Preflight recipe: `Temp\zyvo\paren_check.py` (strings/comments stripped -
  the raw-count check_refs.py gives false positives) + xml.dom.minidom parse
  of every res XML.
