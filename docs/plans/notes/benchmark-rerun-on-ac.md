# Runbook — re-run the cumulative benchmark on AC power

**For a fresh Claude Code session.** Self-contained: read this file, `CLAUDE.md` and `docs/plans/PROGRESS.md` (ledger row
"Cumulative A/B · M0 baseline vs head"), nothing else is needed to start.

## Why

The cumulative comparison of the M0 baseline and today (`benchmark/README.md` → "Results — cumulative: M0 baseline vs M3
so far", commit `2abfc30`) ran **on battery with macOS Low Power Mode on** (89% → 38%). Both sides ran alternately under
the same conditions, so the large deltas and all allocation numbers hold, but:

- Low Power Mode caps the CPU frequency and slows the JIT's compiler threads, which changes which compiled shape a fork
  settles into — the JIT-sensitive rows (`complex` read @1: −5% / −5% / −29% across runs; `simple` write @25) may be
  artefacts;
- the avro4k ÷ Apache ratios came out different from the M0 table measured on AC, so they are not reliable;
- identical Apache code drifted up to ±7.2% between sides, so every delta under ~10% is uncertain;
- absolute ops/s are 20–55% below normal.

The user asked for a re-run on AC power, from a clean session, to get numbers that can be trusted.

## Preconditions — check before starting, and before *every* invocation

1. **AC power, Low Power Mode off:** `pmset -g batt` says `'AC Power'`; `pmset -g | grep lowpowermode` says `0`
   (`sudo pmset -a lowpowermode 0` — the user runs it with `! sudo pmset -a lowpowermode 0` if needed). Head's build
   refuses to benchmark otherwise (`:benchmark:checkBenchmarkPower`, run before every JMH task); **the base commit
   predates that guard**, so check `pmset` yourself before each base invocation. If power drops mid-run, discard that
   invocation and redo it.
2. **Nothing else running:** no other agent, no Gradle build, no IDE indexing if avoidable. One JMH process at a time.
3. Wrap every invocation in `caffeinate -i` (the host sleeps otherwise).
4. Record `sysctl -n machdep.cpu.brand_string`, `sw_vers -productVersion`, `java -version`, and the `pmset -g batt`
   line at the start and end of the session in the results.

## What to run

Delegate to **one** `general-purpose` agent (keeps the orchestrator's context small; the agent's tool output is large),
in a worktree, with the brief below. Nothing else runs while it works.

- **Base:** `4508a13` (M0 baseline). **Head:** `bd99294` — the same pair as the battery run, so the new section replaces
  it like for like. (Commits after `bd99294` touch no benchmarked path: a duration refactor, docs, the power guard.)
  Check out the base in a second worktree (`git worktree add <scratch> 4508a13`); remove it at the end.
- **Protocol** — exactly the battery run's (`benchmark/README.md` → the cumulative section, "Method"): the plugin's six
  per-workload tasks `simple-read`, `simple-write`, `complex-read`, `complex-write`, `lists-read`, `lists-write`
  (1 fork, 5 × 1 s warmup, 5 × 3 s measurement), **3 invocations per side, alternating** base, head, base, head, base,
  head; then `:benchmark:benchmarkAlloc` EA-off (`-PallocJmhArgs='-jvmArgsAppend -XX:-DoEscapeAnalysis'`) on the avro4k
  end-to-end rows, 1 invocation per side. ~3 h 15 min in total.
- **Then, if time allows — the two JIT-sensitive rows,** on head only unless a base comparison is needed:
  `Avro4kComplexBenchmark.read` @1 and `Avro4kSimpleBenchmark.write` @25 with `benchmarkAlloc` at 9 forks, per-fork
  scores listed, and one extra run with `-PallocJmhArgs='-jvmArgsAppend -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining'`
  captured to a scratch file: report whether slow-mode forks inline differently (e.g. a megamorphic call site on the
  `AvroSchema` hierarchy that fast forks inline), in ≤ 15 lines. A structural cause would be a platform-neutral fix; a
  pure JIT-lottery effect is not fixable under locked decision 6 and is only recorded.

## Brief to give the agent (paste it, adjusted if the preconditions changed)

> You re-run the cumulative benchmark comparison of avro4k on **AC power with Low Power Mode off**, following
> `docs/plans/notes/benchmark-rerun-on-ac.md` ("Preconditions", "What to run") exactly. You are the only process
> building or benchmarking on this machine (Apple M2 Pro, 16 GB): never run two Gradle/JMH tasks at once; check
> `pmset` before every invocation (base commit has no guard); `caffeinate -i` around every run; long runs with
> `run_in_background` and polling (a Bash call times out at 10 min). Never use `git stash`; never touch source code
> (harness fixes: report, don't make them). **Deliverable:** in `benchmark/README.md`, replace the body of the section
> "Results — cumulative: M0 baseline vs M3 so far (4508a13 vs bd99294)" with the AC-power results, same table layout
> (per workload × param × read/write: avro4k M0, avro4k now, Δ%, verdict better/worse/same — "same" when under 10% and
> not separated across the 3 invocations — and avro4k ÷ Apache Specific at M0 and now; then EA-off B/op), plus the
> machine/power lines, the Apache drift between sides (noise floor), and a 5–10 line plain-language verdict: where
> avro4k stands vs M0 and vs Apache, which gaps remain, and which planned performance changes target them (see the
> plan's structural-performance milestone: frame-stack encoder/decoder, union branch tables, string/bytes fast paths,
> polymorphic decode allocations, reordering encoder without closures). Keep a one-paragraph note that the previous
> version of this section was measured on battery and is superseded (its commit: `2abfc30`). Then the JIT-sensitive rows
> part if time allows. One commit, `perf(benchmark): cumulative comparison re-measured on AC power`, ending with
> `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`; do not edit `docs/plans/PROGRESS.md`. Final report
> ≤ 20 lines: commit, wall time, per-workload read/write M0 → now Δ% and avro4k ÷ Apache M0 → now, EA-off B/op
> deltas, anything worse than M0, and how the AC numbers differ from the battery ones.

## After the agent reports (orchestrator)

1. Review the diff (README only), cherry-pick onto `v3/m2-foundation`, remove the worktree.
2. Update the ledger row "Cumulative A/B · M0 baseline vs head" with the AC numbers, and a decision-log row if the AC run
   changes any conclusion of the battery run (in particular `complex` read @1 and the Apache ratios).
3. Commit (`docs(plans): …`), then give the user the per-workload table and the plain-language verdict.
