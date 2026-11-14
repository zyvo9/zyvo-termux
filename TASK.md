# TASK — tool cards + true streaming (apk-38)

GOAL: (1) every tool row becomes a compact card, collapsed = 1 line, tap expands
(40 lines) then full; (2) `+N −N` diff chip expands to show the real changed
lines; (3) reasoning/answer text streams token-by-token via `message.part.delta`
(today the thought row pops in only when reasoning ENDS, together with the tool).

Decisions (user): A2 = collapsed 1 line · B2 = cards for ALL tools.

## Steps
- [x] TASK.md (this file)
- [x] ChatItem: `showDiff` flag
- [x] item_tool.xml: card shell + head row + output + meta + diff body + sweep
- [x] drawable/ic_lc_chevron_down.xml (12dp chevron)
- [x] strings: `lines_tap_expand` ("%1$d lines · tap to expand")
- [x] ChatAdapter: bindTool stages (1 → 40 → all), diff pill + diff body spans,
      ToolVH fields
- [x] ChatFragment: `message.part.delta` → append to thought/assistant,
      partTypes map, throttled thought flush (60ms), create thought on empty
      reasoning part
- [x] balance/XML checks, commit, push, CI build, install, screenshot verify

## Now
Code done, refs checked. Next: commit -> push -> CI build -> install -> screenshot verify.

## Notes
- Engine 1.17.9 publishes `message.part.delta` {sessionID, messageID, partID,
  field:"text", delta} for BOTH reasoning and text (`processor.ts:398/785`);
  `message.part.updated` only fires at part start (empty text) and part end.
  Our client ignored deltas → text only appeared at the end (masked by the
  reveal animation), reasoning appeared with the tool call = the reported bug.
