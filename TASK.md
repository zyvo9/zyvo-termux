# TASK - Zyvo Code on Termux fork: UI + controls pass

GOAL: Turn the fork's chat UI into the real zyvo experience: no duplicate/racing
messages, no premature "Thinking" row, no "Worked for 3s" rows (show the real
terminal cards), ChatGPT-style session drawer on the hamburger, Terminal entry,
no Termux EXIT/Wake-lock notification actions, and Plan/Build agent modes in the
composer - every zyvo visual feature kept.

Repo: C:\Users\Admin\zyvo-termux (branch master, CI: .github/workflows/zyvo-build.yml)

## Steps
- [x] 1. Engine runs inside fork; native pkg install verified (build 9).
- [x] 2. Message flow: kill duplicate user echo, smart autoscroll (no jumping),
      scroll-to-bottom button. (build 10/12)
- [x] 3. Thinking: no placeholder row on send; row appears only with real
      reasoning; expandable only after it finishes. (build 10)
- [x] 4. Remove "Worked for Xs" summary rows; tool cards render directly. (build 10)
- [x] 5. Hamburger -> ChatGPT-style session drawer (list, new chat, terminal,
      settings, rename/delete). (build 10)
- [x] 6. Plus menu: Terminal opens TermuxActivity; Settings too. (build 10)
- [x] 7. Remove Termux notification EXIT + Wake-lock action buttons. (build 10)
- [x] 8. Composer: Plan/Build agent mode selector sent as `agent` in payload. (build 10)
- [x] 9. Keep zyvo visuals: thought rows, tool cards + diff stats, output
      preview/expand, copy, timestamps, model + effort picker, stop button. (build 10)
- [x] 10. Build via CI, install over wireless adb, verify on device.
- [x] 11. Word-by-word streaming: revealTick (32ms, plain text while revealing,
      markdown on completion) + bottom-anchored scrollBy (no scrollToPosition
      on the last row -> that was the "text jumps to the front" bug). (build 12)
- [x] 12. Composer polish: 56dp input / 15sp, 22dp radius, 40dp send, bottom
      chips 9sp + maxWidth 150dp so typing is not cramped. (build 13)
- [x] 13. Working row stays visible for the whole turn with elapsed seconds;
      removed from per-message `message.updated completed` (fires per agent
      step), turn ends only on `session.idle`. (builds 13/14)

## Decisions
- Session drawer lives in activity_chat.xml (DrawerLayout); fragment populates it.
- Agent mode stored in Prefs (`agent_mode`, default "build"), sent per prompt.
- Tool cards never grouped; each card is its own row.
- Autoscroll only when the user is pinned to the bottom; follow with scrollBy.
- Assistant text is revealed word-by-word from `fullText`/`shown`; markdown is
  applied only when the reveal finishes.
- Working row is a turn header (after the user bubble), not a summary row.

## NOW: build 14 (7f40e44) installed on the phone; waiting for user's on-device
verification of word-by-word streaming, persistent Working row, composer size.
