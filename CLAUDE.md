# avro4k

## Current initiative: v3 — Kotlin Multiplatform + performance

| What | Where |
|---|---|
| Plan (copy of record) | `docs/plans/v3-kmp-and-performance.md` — **read this first** |
| Progress ledger | `docs/plans/PROGRESS.md` — current milestone, status, measured numbers |
| Per-unit findings | `docs/plans/notes/` |

**Before starting work:** read `PROGRESS.md`, pick the next unit, and do not re-plan what is
already decided. The locked decisions are listed at the top of the plan; a unit that appears to
require re-opening one of them is a signal to stop and ask, not to decide again.

**Never** reintroduce `org.apache.avro.Schema` into the core public API, and never restart from the
stale `kmp` branch wholesale — it is 5+ months behind `main` and is a source of ideas and files to
cherry-pick, not a base to merge.

## Working rules

- **Bugs that predate the migration are not fixed during it** (incorrect spec implementation included), unless the
  pure Kotlin code written for multiplatform support fixes them as a side effect. Reproduce on `main` first; if it
  reproduces, write it in `docs/plans/notes/backlog-pre-existing-bugs.md` (repro, cause, fix sketch, issue text) for the
  user to handle apart. New cross-platform divergences are migration scope.
- **Never write plan step codes (M1, M3-09, B6, C2, A5, …) in source code** — comments, KDoc, test
  names, strings, build scripts. They live only in `docs/plans/`; explain the *why* in plain words instead.
- Benchmarks are strictly serialized — never run two benchmark tasks concurrently, on any machine.
- Benchmarks run on **AC power with macOS Low Power Mode off** — `:benchmark:checkBenchmarkPower` enforces it before
  every JMH task; commits older than the guard must be checked by hand with `pmset -g batt` / `pmset -g`.
- Nothing is "done" until `PROGRESS.md` is updated **and committed**.
- Measured numbers go into `benchmark/README.md` in the same commit as the change that produced
  them, so a regression is always bisectable.
- Anything touching `core/api/*.api` runs alone (concurrent `apiDump`s conflict).

## Verification commands

```bash
./gradlew :core:test :confluent-kafka-serializer:test :kotlin-generator:test
./gradlew apiDump && git diff --exit-code   # intentional API changes only
./gradlew spotlessApply
./gradlew actionsBeforeCommit               # the repo's own pre-commit aggregate
./gradlew :benchmark:benchmark              # full perf suite (serialized!)
```
