# TASK — Zyvo Code on Termux fork: UI + controls pass

GOAL: Turn the fork's chat UI into the real zyvo experience: no duplicate/racing
messages, no premature "Thinking" row, no "Worked for 3s" rows (show the real
terminal cards), ChatGPT-style session drawer on the hamburger, Terminal entry,
no Termux EXIT/Wake-lock notification actions, and Plan/Build agent modes in the
composer — every zyvo visual feature kept.

Repo: C:\Users\Admin\zyvo-termux (branch master, CI: .github/workflows/zyvo-build.yml)

## Steps
- [x] 1. Engine runs inside fork; native pkg install verified (build 9).
- [ ] 2. Message flow: kill duplicate user echo, smart autoscroll (no jumping),
      scroll-to-bottom button.
- [ ] 3. Thinking: no placeholder row on send; row appears only with real
      reasoning; expandable only after it finishes.
- [ ] 4. Remove "Worked for Xs" summary rows; tool cards (Terminal + command +
      output) render directly.
- [ ] 5. Hamburger -> ChatGPT-style session drawer (list, new chat, terminal,
      settings, rename/delete).
- [ ] 6. Plus menu: Terminal opens TermuxActivity; Settings too.
- [ ] 7. Remove Termux notification EXIT + Wake-lock action buttons.
- [ ] 8. Composer: replace "Full access" with Plan/Build agent mode selector,
      sent as `agent` in the prompt payload.
- [ ] 9. Keep all zyvo visuals: thought rows, tool cards + diff stats, output
      preview/expand, copy, timestamps, model + effort picker, stop button.
- [ ] 10. Build via CI (apk-<run>), install over wireless adb, verify on device
      with screenshots.

## Decisions
- Session drawer lives in activity_chat.xml (DrawerLayout); fragment populates it.
- Agent mode stored in Prefs (`agent_mode`, default "build"), sent per prompt.
- Tool cards never grouped; each card is its own row.
- Autoscroll only when the user is pinned to the bottom.

## NOW: implementing steps 2-9 in ChatFragment/ChatAdapter/layouts/ApiClient/Prefs/TermuxService.
