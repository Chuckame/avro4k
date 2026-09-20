# v3 KMP + performance — progress ledger

**The one file to read to know where things stand.** Plan of record:
[`v3-kmp-and-performance.md`](v3-kmp-and-performance.md). Per-unit findings: [`notes/`](notes/).

Maintained by the **orchestrator** (the main Claude Code session), never by the sub-agent that did
the work — so the ledger stays consistent even if an agent fails midway. A unit is not done until
its row here is updated **and committed**.

**M0 is complete** (A1–A8). Next: **M1 — cheap perf wins on `main`** (C2, C3, C9, and the C5
`readBytes()` fix), shippable as v2.x.

**Read this before planning any performance work:** the M0 baseline changed two of the plan's
premises. avro4k reads **~2x slower than Apache `SpecificData`** in every workload — the original
"wins on flat records, loses as nesting grows" framing came from only ever comparing against
`ReflectData`. And **allocation does not explain the read gap**, so `gc.alloc.rate.norm` alone cannot
police decode work. Details in `benchmark/README.md` and [`notes/a8.md`](notes/a8.md).

## Ledger

| Unit | Status | Branch / PR | Commit | Result |
|---|---|---|---|---|
| M-1 plan persistence | done |  `docs/v3-plan` | _initial docs commit_ | `CLAUDE.md`, plan, ledger, `project` memory entry |
| A1 benchmark methodology fixes | done | `perf/baseline` | `0c14e9b` | all 9 `read()`s now consumed; sinks hoisted; `@Param` 1/15/200 · 1/25/500 · 100/10000. Matrix 18 → 48, full run ~10 → ~25 min. See [`notes/a1.md`](notes/a1.md) |
| A2 allocation as a first-class metric | done | `perf/baseline` | `c554121` | `:benchmark:benchmarkAlloc` runs the plugin's JMH uber-jar with `-prof gc`; narrow with `-Pbench`/`-Pparams`. `-prof gc` confirmed supported, no plain-JMH fallback needed. See [`notes/a2.md`](notes/a2.md) |
| A3 micro-benchmarks for hot paths | done | `perf/baseline` | `ea0988b` | 6 families / 39 combos + a `micro` config. Reordering encoder −41% write / +544 B/op; polymorphic exactly 32 B/value; warm schema cache alloc-free. See [`notes/a3.md`](notes/a3.md) |
| A4 baseline captured | done | `perf/baseline` | `e404434` | 87 combos, throughput + B/op, in `benchmark/README.md`. See [`notes/a4.md`](notes/a4.md) |
| A5 per-library models + equivalence gate | done | `perf/baseline` | `6689f2e` | all 9 previously-silent failures now run; byte-identity gate green (one documented `jackson/complex` exemption). See [`notes/a5.md`](notes/a5.md) |
| A6 Apache fast-reader variants | done | `perf/baseline` | `36e5346` | 3 `GenericData` read classes, `fastReader` @Param. **1.9–2.6x on record-shaped payloads.** Reflect excluded by Avro itself. Matrix 48 → 64. See [`notes/a6.md`](notes/a6.md) |
| A7 Apache SpecificData variant | done | `perf/baseline` | `4182c4f` | `SpecificCompiler` codegen from the canonical schema; zero schema exemptions. **avro4k reads 1.6–2.4x slower than Apache's fastest path.** Matrix 64 → 80. See [`notes/a7.md`](notes/a7.md) |
| A8 re-capture baseline | done | `perf/baseline` | `4508a13` | 119 combos, **0 failures**. Supersedes A4. See [`notes/a8.md`](notes/a8.md) |
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
| 2026-09-19 | A1 | Hoisted a discarding `nullOutputStream`-backed sink rather than a reusable `kotlinx.io.Buffer` + `clear()`: a `Buffer` retains bytes, so avro4k would pay a memcpy that Apache/Jackson do not. avro4k must `flush()` per op — `encodeToSink` deliberately does not. |
| 2026-09-19 | A1 | `@Param` on an `internal` Kotlin `@State` class needs `@JvmField final var`; plain `var` leaves the backing field private and JMH sets it reflectively. `final` is required because this module's allopen plugin opens `@State` members and `@JvmField` rejects open properties. |
| 2026-09-19 | A2 | `-prof gc` verified supported on the plugin's self-contained JMH uber-jar, so the plan's plain-JMH-source-set fallback is **not** needed. `gc.alloc.rate.norm` (B/op) is the primary acceptance metric for all workstream-C changes. |
| 2026-09-19 | A2 | The jar task `:benchmark:mainBenchmarkJar` is registered from the kotlinx-benchmark plugin's own `afterEvaluate`, so `benchmarkAlloc` must be registered inside `afterEvaluate` too — a top-level `tasks.named(...)` fails at configuration time. |
| 2026-09-19 | A2 | `benchmark/` does **not** apply spotless (only `core`, `kotlin-generator`, `gradle-plugin` do) — formatting there is by hand, and `spotlessApply` passing says nothing about this module. |
| 2026-09-19 | A2 | ~5 s of JVM/harness overhead per benchmark method, so `-Pbench` narrowing is mandatory in practice; the full 48-benchmark alloc matrix is not something to run casually. |
| 2026-09-20 | A3 | C1 (per-structure encoder/decoder allocation) and C2 (`resolveFields` per record instance) **cannot be told apart from outside the library** — they happen in the same call with no public seam, so the nested-record family measures their sum. Attributing a change to one of them needs async-profiler `-e alloc` or a temporary core-side counter. |
| 2026-09-20 | A3 | Escape analysis puts a floor under two families: nested `write` at depth 1 reports ~0 B/op and polymorphic at `shapeCount=10` under-reports the `Pair` by ~3x. Read those at depth 3/8 and at 1000 respectively, or the numbers mislead. |
| 2026-09-20 | A8 | **Allocation cannot police the decode gap** — a revision to the plan's "bytes/op is the primary acceptance metric". avro4k is within 1.2–1.4x of `SpecificData` on read bytes/op (and allocates *less* on `simple` @500) while running ~2x slower; nullable strings cost **−29% throughput at ±0 B/op**. Conversely avro4k allocates 4.4x on `complex` @200 write yet runs within 1.08x. bytes/op remains right for the **KMP migration** (no escape analysis on JS/native) and the **write** path; decode must be judged on throughput directly. |
| 2026-09-20 | A8 | The read gap is **flat across sizes** (1.5–2.2x at every parameter value), which points at fixed per-value/per-structure overhead rather than anything compounding with nesting. This strengthens C1/C4 and weakens the assumption that nesting depth is the primary driver. |
| 2026-09-20 | A7 | **The comparison was flattering avro4k, and the corrected picture changes the program's framing.** Against Apache `SpecificData` + fast reader — the configuration a performance-minded Apache user actually deploys — avro4k **reads 1.6–2.4x slower in all three workloads**, and writes range from 1.35x slower (`lists`) to 1.20x faster (`simple`). `SpecificData` is a flat 2.0–2.6x over `ReflectData`. The original plan's "avro4k wins on flat records, loses as nesting grows" was an artifact of comparing only against Apache's slowest path. **Decode is now unambiguously the priority** — which the A4 allocation data independently said (read allocates 5.7x write). |
| 2026-09-20 | A7 | Codegen is `Avro.schema<T>()` → checked-in `.avsc` (namespace rewritten only) → `SpecificCompiler` via a 20-line Gradle task. Rejected an Avro Gradle plugin, `avro-tools` (~70 MB, and its `SpecificCompiler.main` compiles *protocols* only — verified by disassembly), checked-in generated Java, and build-time `.avsc` generation (cyclic: needs the Kotlin model from the same source set). Drift is caught by the A5 gate comparing `SCHEMA$` to `Avro.schema<T>()` on every trial. |
| 2026-09-20 | A7 | The whole canonical schema survives codegen — `char` logical type, `date`/`timestamp-millis`, the sealed `Partner` union. **Nothing in avro4k's model was weakened to make another library fit.** Two asymmetries are disclosed in code rather than hidden: `balance` lands as `String` (so Apache's `complex` write skips a per-record `BigDecimal.toString()`) and `gender` decodes to `Integer`. |
| 2026-09-20 | A6 | **The fast reader is *enabled* by default in Avro 1.12.1**, not disabled — `fastReaderEnabled = System.getProperty("org.apache.avro.fastread", "true")`. Verified in the shipped bytecode. It was opt-in when `FastReaderBuilder` was introduced in 1.11.x; 1.12 flipped it. So for `GenericData`/`SpecificData` users, fast reading is already what they get. |
| 2026-09-20 | A6 | **`FastReaderBuilder.isSupportedData` is an exact-class check** (`getClass() == GenericData.class \|\| == SpecificData.class`), verified in bytecode. `ReflectData` and *every* `GenericData` subclass are permanently excluded, and `setFastReaderEnabled(true)` on one writes a field that is then ignored. A `fastReader` @Param on the reflect benchmarks would have produced `true` rows silently running the slow path — exactly the class of silent lie A5 removed. |
| 2026-09-20 | A6 | Fast vs slow reader, like-for-like: `complex`@15 **1.94x**, `complex`@200 **2.04x**, `lists`@10000 **2.62x**, `simple`@500 1.12x (byte-copy dominated). The generic rows' *absolute* numbers are not comparable to the reflect/avro4k rows (generic decode builds `GenericRecord`s, not model objects) — only the ratio is meaningful, and the KDoc says so. |
| 2026-09-20 | A5 | **A4's root-cause diagnosis was wrong and is corrected.** The Apache read failures were *not* Kotlin `val` finality — `Field.set` prints field modifiers in its message and a finality violation would be `IllegalAccessException`. The cause is a type mismatch: `ReflectData` needs the `java-class` schema hints to map decoded values back (`GenericData$Array` → `Array<String>`, `Integer` → `Byte`). Counter-evidence was already in the baseline: `lists` Apache read worked against an all-`val` model. |
| 2026-09-20 | A5 | **Rejected an optimisation that would have flattered avro4k's rival** — an identity cache over `getCustomEncoding` is worth **+69% Apache write throughput**, but no real Apache user gets it, so applying it would make the comparison dishonest in avro4k's favour... in the wrong direction. Recorded in code so nobody "helpfully" adds it later. |
| 2026-09-20 | A5 | Using **two** `ReflectData` instances costs 40% of Apache write throughput (static `ACCESSOR_CACHE`, deep `Schema.equals` on a bucket collision). One shared instance is now used, which also makes the `@Setup` mutation of the global singleton idempotent. A benchmark harness detail worth this much is worth writing down. |
| 2026-09-20 | A5 | Byte-identity holds strictly for avro4k/Apache/Jackson on `simple` and `lists`, and for Apache on `complex` in both directions. **One exemption:** `jackson/complex` — jackson-dataformat-avro buffers every Avro map into a `HashMap` with no setting to disable it, so the gate falls back to decoded-data equality there. Stale exemptions fail the gate. |
| 2026-09-20 | A5–A8 | **Added at the user's request.** A4 showed the comparison suite silently omits every Apache/Jackson *read* number. Rather than document that as a permanent limitation, give each library a model it can actually round-trip and **prove equivalence in `@Setup`** (same schema, byte-identical output) so a missing comparison fails loudly instead of vanishing from the report. The user also asked to broaden the Apache side beyond `ReflectData` to `SpecificData` and the fast reader (off by default), since comparing against only the slowest Apache configuration flatters avro4k. |
| 2026-09-20 | A4 | Apache `ReflectData` **cannot read into Kotlin data classes** on JDK 21 (final `val` fields, set reflectively). Verified to fail identically on pristine `main`, so it pre-dates M0. There is therefore **no avro4k-vs-Apache read comparison** for `complex`/`simple`; the plan's context table (+15% / −7% read) is not reproducible and must not be used as a target. |
| 2026-09-20 | A4 | Fixing dead-code elimination cost **8.9%** of avro4k's apparent read throughput (`simple` @ 25: 206,652 → 188,278 ops/s). Every pre-M0 avro4k read figure was inflated by about that much. |
| 2026-09-20 | A4 | Baseline redirects the perf work toward **decode**: avro4k read allocates 5.7× its write path (1.74 MB vs 305 kB per op on `complex` @ 200), while the collection write path is already allocation-flat (243 B/op at 100k longs). The `lists` write gap to Jackson is not an allocation problem. |
| 2026-09-20 | A3 | The `ReorderingCompositeEncoder` path was **confirmed, not assumed**: a throwaway reflection probe read `Avro.recordResolver` and printed the `EncodingWorkflow` per schema (`matching → ExactMatch`, `swapped`/`reversed` → `NonContiguous`). |
| 2026-09-19 | A1 | `lists` reshaped from 10 × 10,000 to 1 × `entryCount` × 100 values, so the pre-A1 `lists` numbers in `benchmark/README.md` are **not** comparable to anything measured from A4 on. |

## Measured numbers

**The baseline is `benchmark/README.md` → "Results — M0 baseline"** (2026-09-20, Apple M2 Pro,
OpenJDK 21.0.11). Every later claim is measured against it, and numbers are committed alongside the
change that produced them.

The pre-M0 table that used to sit here (`simple` +15%/+25%, `complex` −7%/−17%, `lists` +39%/−15%)
has been **deleted rather than kept for reference**: it was produced on different hardware, with
dead-code elimination inflating the read side, with harness allocation inside the measured write,
and against an Apache read path that does not run at all on this JDK. Keeping it would invite
false comparisons.

Headline, post-M0: avro4k **write** leads Apache by +71…79% on `complex` at every size. avro4k
**read** allocates 5.7× what write does — decode is where the work is.
