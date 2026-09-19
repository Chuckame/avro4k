# v3 KMP + performance — progress ledger

**The one file to read to know where things stand.** Plan of record:
[`v3-kmp-and-performance.md`](v3-kmp-and-performance.md). Per-unit findings: [`notes/`](notes/).

Maintained by the **orchestrator** (the main Claude Code session), never by the sub-agent that did
the work — so the ledger stays consistent even if an agent fails midway. A unit is not done until
its row here is updated **and committed**.

**Current milestone: M0 — Measure** (benchmark methodology + baseline, on `main`).

## Ledger

| Unit | Status | Branch / PR | Commit | Result |
|---|---|---|---|---|
| M-1 plan persistence | done |  `docs/v3-plan` | _initial docs commit_ | `CLAUDE.md`, plan, ledger, `project` memory entry |
| A1 benchmark methodology fixes | todo | — | — | DCE on `read()`, harness alloc in `write()`, `@Param` sizes |
| A2 allocation as a first-class metric | todo | — | — | verify `-prof gc` on the kotlinx-benchmark JMH jar **first** |
| A3 micro-benchmarks for hot paths | todo | — | — | `micro` package, one per C1/C2/C3/C4/C6/C7/C8 suspect |
| A4 baseline captured | todo | — | — | throughput + bytes/op into `benchmark/README.md` |
| M1 · C2 workflow memoization | todo | — | — | — |
| M1 · C3 collection serializer cache | todo | — | — | — |
| M1 · C9 fingerprint memo + enum boxing | todo | — | — | — |
| M1 · C5 `readBytes()` offset fix | todo | — | — | correctness fix + regression test |
| M1 review | todo | — | — | `feature-dev:code-reviewer` |
| M2 · B0 build conventions | todo | — | — | — |
| M2 · B1 `AvroSchema` → `commonMain` | todo | — | — | — |
| M2 · B6 caches → `expect`/`actual` | todo | — | — | — |
| M3 decomposition (`Plan` agent) | todo | — | — | writes `notes/m3-decomposition.md` |
| M3 · B2 own codec ABI | todo | — | — | — |
| M3 · B3 core → `commonMain`, Jackson dropped | todo | — | — | — |
| M3 · B4 serializer split | todo | — | — | — |
| M3 re-baseline + review | todo | — | — | **regressions hide here** |
| M4 · B5 CRC-64-AVRO + OCF in common | todo | — | — | discretionary; may slip to v3.1 |
| M4 · B7 downstream modules | todo | — | — | — |
| M4 · B8 apiDump, publication, migration guide | todo | — | — | runs alone (`apiDump`) |
| M5 decomposition (`Plan` agent) | todo | — | — | C1 first |
| M5 · C1 frame-stack encoder/decoder | todo | — | — | riskiest change in the plan |
| M5 · C4 union branch tables | todo | — | — | — |
| M5 · C5 string/bytes fast paths | todo | — | — | — |
| M5 · C6 polymorphic decode allocations | todo | — | — | — |
| M5 · C8 `ReorderingCompositeEncoder` without closures | todo | — | — | — |

Status values: `todo` · `in progress` · `blocked` · `done`.

## Decision log

Records *why* a unit was done the way it was, so a later session does not re-decide it.

| Date | Unit | Decision |
|---|---|---|
| 2026-09-19 | M-1 | Plan persisted in git (`docs/plans/`) rather than `~/.claude/plans/`, which a future session cannot find. |

## Measured numbers

Live numbers live in `benchmark/README.md`, committed alongside the change that produced them.
Reference baseline (pre-M0, MacBook Air M2, vs Apache `ReflectData`):

| Workload | avro4k read | avro4k write |
|---|---|---|
| `simple` (25 flat records) | +15% | +25% |
| `complex` (15 nested `Clients`) | −7% | −17% |
| `lists` (10 × 10,000 `List<Long>`) | +39% | −15% |

These predate the A1 methodology fixes, so they are **indicative only** — A4 replaces them.
