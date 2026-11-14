# TASK — Grok-black UI + full error system + exact Codex-style tool rows

GOAL (user, 2026-11-14): the zyvo-termux chat app must look like the two
reference screenshots on the phone:
- SS-2 (Grok app): pure black, gray user bubble, plain white assistant text,
  "Thought for 4s >" row, action icons under a reply, round composer.
- SS-1 (Codex-style): flat tool rows — rounded-square icon, gray label
  ("Ran command" / "Writing file" / "Edited file"), BOLD WHITE title,
  +N −N in front for writes, tap expands to 10 lines.
Plus: EVERY engine error type must be visible in the UI, rendered UNDER the
assistant reply (where "interrupted" shows after an abort).
User choices: 1A full Grok black (amber only on send), 2A copy+regenerate+
share, 3A all phases in one build.

## Steps
- [x] 1. Root cause of "rate limit but nothing shows" (session.status retry
      was ignored) — fixed in 2bd5849, build 44 green.
- [x] 2. TASK.md written.
- [x] 3. Icons: lightbulb, copy, refresh, share (lucide, same 24dp stroke-2
      format as the existing ic_lc_* set).
- [x] 4. item_tool.xml rewrite: 26dp rounded-square icon box (1dp line
      stroke) + gray label + +N −N + bold white title + chevron; body opens
      to 10 lines, tap stages 10 -> full -> collapse.
- [x] 5. item_thought.xml: lightbulb icon, "Thought for 4s >" format.
- [x] 6. item_message_assistant.xml: action row copy / refresh / share.
- [x] 7. item_error.xml: left red border panel for every error type.
- [x] 8. Palette: values-night/zyvo_colors.xml -> Grok black (#000 bg,
      #FFFFFF text, #9A9A9A secondary, #2A2A2A user bubble, #1A1A1A card),
      amber only for the send button.
- [x] 9. Drawables: bg_icon_box, bg_error_panel, white tab underline, white
      FAB, gray user bubble.
- [x] 10. ChatAdapter: bindTool rewrite (label/title/diff/stage), bindThought,
      bindMessage actions + interrupted footer, ErrorVH, iconFor update.
- [x] 11. ChatFragment: error NAME mapping (Aborted / Auth / API / ContextOverflow
      / OutputLength / StructuredOutput / Unknown), interrupted marking,
      regenerate + share wiring.
- [x] 12. Strings.
- [ ] 13. check_refs + XML parse, push, poll CI, install, screenshot compare.

## Decisions
- Tool rows are FLAT (no card background) — Codex SS-1.
- Write/edit show +N −N BEFORE the title.
- Expand = 10 lines first, then full, then collapse.
- Errors = left-border panel directly under the reply, never a big card.
- Abort = "· interrupted" in the reply footer (CLI parity).
- Grok palette, amber kept ONLY on the send button (user choice 1A).

## NOW:
Step 13 — preflight passed (java balanced, 118 xml parse, refs resolve);
commit + push + CI poll, then install and screenshot-compare.
