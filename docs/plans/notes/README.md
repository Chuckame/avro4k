# Per-unit findings

One file per unit of work (`a1.md`, `b1.md`, `c2.md`, `m3-decomposition.md`, …), written by the
sub-agent that did the unit. Agents return at most 20 lines to the orchestrator; everything else —
what was tried, what was rejected, surprises, follow-ups — goes here so the next session can pick it
up without re-deriving it.

Suggested shape:

```markdown
# <unit> — <title>

**Outcome:** done / partial / blocked
**Commit:** <sha>

## What changed
## What was measured
## What was rejected, and why
## Follow-ups
```
