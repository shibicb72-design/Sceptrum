# Token budget: how to measure

The Claude Code config in `.claude/` (opusplan, Haiku/Sonnet subagents, read-deny rules,
`scripts/gradle-brief.ps1`, status line) is meant to cut tokens per ticket. Measure it; don't assume it.

## Before / after on the same ticket
1. Pick one real ticket. Note the time, then run it once with the old setup.
2. Afterwards record:
   - `npx ccusage@latest blocks` (or `blocks --recent`): tokens and cost of the 5-hour block(s) the ticket used.
   - `/usage` in the Claude Code session: the 5-hour and weekly limits used.
   - The status line (`[model] ctx N% | 5h N%`) at the end of the ticket.
3. Run the same (or an equivalent) ticket with the new setup and record the same three numbers.
4. Add one change at a time (e.g. only gradle-brief first, then the subagents, then opusplan), so a
   saving or a regression can be traced to a single change. `/clear` between tickets.

## Notes
- The main conversation should never read PNGs, full Gradle logs or `build/` files; if the context % jumps
  after a build, check that the build went through `gradle-runner`.
- Headroom (github.com/headroomlabs-ai/headroom) is only a possible later experiment, not part of this
  setup: it has open Windows bugs, and routing a subscription (OAuth) session through it is a
  Terms-of-Service grey area.
