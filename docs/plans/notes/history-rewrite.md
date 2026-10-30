# v3 — what changed against v2, code quality, and the history rewrite plan

Written 2026-10-30, for the user's approval. **Nothing is rewritten yet.** `v3/m2-foundation` is pushed to `origin`
(Chuckame/avro4k) at `d6a0b7b0`.

"v2" below is upstream `avro-kotlin/main` at `48b4f24f`, the newest v2. The branch is based on `9fb0f5ee` and merged
upstream twice (`90d44254`, `668f6a06` up to `56dc7da8`). **Upstream has 5 commits the branch lacks:**

| Commit | What | Effect on v3 |
|---|---|---|
| `91b14734` #559 | int and long decoded as float and double | **Must be ported** to the rewritten decoders (direct, generic, JSON defaults), the validator, and `unsupportedWriterTypeError`'s lists |
| `48b4f24f` #563 | `encodeToStream` flushes its buffered sink | v3 already writes every byte (its own stream path). Keep v3's code and upstream's `EncodeToStreamTest` |
| `9764343b` #557, `be2f19a9` #556, `6c51922a` #553 | kotlinx-serialization 1.12.0, Gradle 9.8.2, plugin-publish 2.2.1 | Version bumps; re-run ACC+KMP and the API dumps |

---

## 1. New features and improvements (user-visible)

**Platforms**
- Kotlin Multiplatform core: JVM, JS (browser and Node.js), and the whole Kotlin/Native matrix. wasm is not supported yet.
- Core has no dependency on Apache Avro, Jackson, okio or kotlinx-io.
- Maven: `avro4k-core-jvm`. The BOM covers it and `avro4k-apache-interop`.

**API**
- `AvroSchema`, avro4k's own sealed schema model, in place of `org.apache.avro.Schema` everywhere in core's API.
  It has JSON parsing (`AvroSchema.fromJsonString` / `fromJsonElement`), JSON writing (`toJsonElement`), and
  `ResolvedSchema` (a schema that is never a union).
- New `avro4k-apache-interop` module (JVM): `toApacheSchema()` / `toAvro4k()`, `GenericData` encoding and decoding (no
  longer deprecated), `AvroObjectContainer`, and the Apache codec entry points.
- Single-object encoding (`AvroSingleObject`) on every platform, with CRC-64-AVRO and parsing canonical form written in
  common code.
- `validateSerialization` on every platform, also for the Apache codecs (Confluent, container files). It is stricter
  than Apache's validator and reports the path of the failing call.
- An `AvroSerializationException` hierarchy (schema generation, schema parse, schema mismatch, malformed data,
  validation). Schema generation errors give the path of the failing element (#267). Malformed input always fails
  with `AvroMalformedDataException` (#269).
- `AvroDecoder.avro` / `AvroEncoder.avro`. `AvroDecoder.currentWriterSchema` is never a union.
- `AnySerializer` becomes experimental (it was internal) and works on every decoder, on defaults and on generic data.
- `uuid` decodes to `kotlin.uuid.Uuid` by default, on the JVM too.
- JVM streams: `encodeToStream` writes every byte. `decodeFromStream` reads exactly one value, as kotlinx-json does.

**Schema generation and defaults**
- `@AvroFixed` types are named after their value class's serial name, or `<record full name>.<property>`.
- Default values follow the Avro 1.12 spec: bytes and fixed defaults are read as code points 0–255, a fixed default
  is padded, a union default comes from the first matching branch (which the generator moves first), and a field
  missing from a record default takes its own default at any depth.
- Two named types with the same full name are rejected.

**kotlin-generator and gradle-plugin**
- Fixed types are generated as named value classes, and `uuid` as `kotlin.uuid.Uuid`.
- Opt-ins required by logical types (`requiresOptIn`) and `fileAnnotations { optIn(…); annotation(…) }`.

**Performance** (JVM, v2 → v3 at `3f5f2833`, `benchmark/README.md`)
- Reads +25% to +56%, stream reads +32% to +75%, stream writes +21% to +63%, `ByteArray` writes +19% to +48%.
- The read work after that (`54e0ad19` → now) adds `complex` reads +23% to +29% and `simple` +66% to +99%. On JS and
  native, reads gain +18% to +52%, and JS `Long` writes are ×9.
- Reads allocate 3% to 50% less. **Open point:** JVM writes allocate more than v2 (chunks allocated per call; v2 used
  kotlinx-io's pool). The write A/B is still to run.

**v2 bugs fixed along the way.** These predate the rule that keeps pre-existing bugs out of the migration. Each one
needs a line in the release notes:
- `decodeFromByteBuffer`: a trailing `bytes` field decoded the whole record and stayed aliased to the caller's buffer
  (`2f062c60`).
- Generic decoder: map values were given the key's schema (`8001e573`); a nullable plain `List` failed (`66d33327`); a
  multi-branch union was read with the first matching branch (`ef9133e6`).
- Stream reads broke once a `bytes` field crossed an 8 KiB segment (backlog #15). `encodeToStream` wrote nothing for
  small values (#13, also fixed upstream by #563).
- Strings with an unpaired surrogate, and an alias equal to its own name (`ccc6c407`, `92c87cbe`).

**Removed:** the kotlinx-io and okio overloads, `Record` / `ListRecord`, `decodeValue()`, `decodeResolving*` and their
fun interfaces, and the `org.apache.avro.limits.*` properties for avro4k's own codecs. The full list, with
replacements, is in `Migrating-from-v2.md`.

## 2. Actual changes against v2 (moves and renames set aside)

`git diff -M50% 48b4f24f HEAD`, excluding `docs/`, `benchmark/`, the generator's expected sources and `yarn.lock`:
**207 files added, 45 deleted, 34 modified, 127 renamed.** Only 3 of the 26 renamed production files are pure
renames (100%). 21 changed by 10% to 50%, so most of what looks like moves is real change.

| Area | v2 | v3 | Nature |
|---|---|---|---|
| Schema model | Apache `Schema`; a 281-line draft in kotlin-generator | `AvroSchema.kt` 749 + JSON 415 + fingerprint 222 | New; the draft moved and completed |
| Binary codec | Apache `BinaryEncoder/Decoder`, kotlinx-io `Buffer` | `internal/codec/` ~1,900 (varint, UTF-8, `ByteArrayEncoder`, `BufferedDecoder`, JVM stream and `ByteBuffer` decoders, validator) | New |
| Decoders | ~1,000 (direct + generic) | ~1,900 direct + `JsonDefaultDecoder` 468 | Rewritten (AvroSchema, reuse per level, exact records, nullable path) |
| Encoders | ~600 | ~1,300 | Rewritten (union tables, small methods, reuse per level) |
| Record resolution, defaults | `RecordResolver` 399, defaults through `GenericData` | `RecordResolver` 575, `AvroDefaults` 215 | Rewritten; defaults read from JSON without Apache |
| Schema generation | visitors on Apache + Jackson | visitors on AvroSchema, `VisitorContext` 277, `AvroSchemaHelpers` 497 | Rewritten, with the fixed naming and error paths |
| Caches | `Cache.kt` (JVM weak maps) | `WeakKeyCache`, `WeakIdentityKeyCache` (expect/actual), `IdentityFirstCache`, `CopyOnWriteWeakTable` | New |
| Serializers | JVM types and Kotlin types mixed | Kotlin stdlib in common, Java types in `jvmMain`, `AnySerializer` split common/JVM/non-JVM | Moved + rewritten without `decodeResolving*` |
| Apache-based features | in core | `apache-interop` (conversion 538, generic decoders and encoders, container, codec adapters) | Moved + adapted |
| Confluent | uses core's Apache API | `CoreBridge` + interop | Adapted, no API change |
| Build | JVM module | KMP conventions, klib ABI dump, `benchmark-common` | New |

Production code: core goes from 6,645 to 9,821 code lines, plus 1,733 in `apache-interop`. Tests go from 5,301 to
20,148 lines. `docs/plans/` adds 17,154 lines in 78 files.

## 3. Code quality review

### 3.1 Comments: twice v2's density, mostly essays

Comment lines per code line (blank lines excluded), production code:

| | comments / code |
|---|---|
| v2 core `src/main` | 1,051 / 6,645 = **0.16** |
| v3 core, every source set | 3,042 / 9,821 = **0.31** |
| v3 tests | 1,056 / 20,148 = 0.05 (v2: 0.01) |

Public KDoc (`Avro.kt`, `Annotations.kt`, `AvroConfiguration.kt`) is fine. The problem is internal code. Kinds of
useless comments, with examples:

1. **History: what the code used to do.** For example `RecordResolver.kt:63-67` ("decoding a 100k-element array of
   records used to perform 200k lookups … when `WeakKeyCache` was JVM-only…") and `AbstractAvroDirectDecoder.kt:93-100`
   ("only allocating as it did before decoders were reused"). This belongs in commit messages.
2. **Measurements and benchmark references in source**, which go stale on the next change.
   `SerializerLocatorMiddleware.kt:45-50` (0.75 ns / 1.24 ns / 2.39 ns and a README section), `RecordResolver.kt:24`
   ("the deepest nesting exercised by the benchmarks is 8"), `AbstractAvroEncoder.kt:39-42` (the 325-byte inlining
   limit). There are 91 such lines in core and apache-interop.
3. **Memory-model and JIT essays.** `RecordResolver.kt:69-80` cites JLS 17.4.5 and the Kotlin/Native `@Volatile`
   contract. `AbstractAvroDirectDecoder.kt:47-51` covers dispatch stubs and field reads versus virtual calls. One line
   ("published once, never mutated: `@Volatile` is enough") says the same thing.
4. **Paragraphs on private fields.** `AbstractAvroDirectDecoder.kt:45-104` has 60 lines of KDoc for six fields;
   `RecordResolver.kt:35-81` has 47 lines for two caches.
5. **Comments that restate the code**, such as "Both getOrPut are inline: no lambda is allocated" and "Identity is
   enough…".

**Rule for the cleanup:** keep a comment only when it states a constraint, invariant or trap the code cannot show, in
one or two lines. Move the why and the numbers to the commit message, where the rewrite puts them anyway. No history,
no measurements, no benchmark names. Target: internal code back near v2's 0.16. Tests: drop narration and keep
one-line "why this case exists" comments only.

### 3.2 Other findings

- **Complexity spent for a few bytes.** `AbstractAvroDirectDecoder` packs the next block's count into
  `decodedCollectionSize` as its bitwise complement (`:148`, `:164`) to avoid a field. Block decoders use their position
  index as an in-use flag (`NOT_IN_USE`) to save 8 B per decoder. A marker union, `NO_ELEMENT_SCHEMA`, stands for "no
  field begun". Each was measured as a whole unit, but none was measured alone. These deserve a second look: keep a
  trick only where the allocation tables show it pays, otherwise use a plain field or flag.
- **A public API file depends on decoder internals.** `AvroDecoder.kt:4,87` imports `NO_ELEMENT_SCHEMA` and downcasts to
  `AbstractAvroDirectDecoder` to build an error message. Move that branch into the decoder (an internal method on the
  error path).
- **`AbstractAvroEncoder` is public** (`@InternalAvro4kApi`) only for `apache-interop`. This is acceptable, but its KDoc
  should say only that, not the inlining rationale.
- **Repository content not meant for upstream:** `docs/plans/` (78 files), `CLAUDE.md`, and the 2,300-line
  `benchmark/README.md` lab journal, which has 25 result sections with old SHAs. Upstream needs "how to run" plus one
  current v2 vs v3 vs Apache table.
- **Commit messages** contain plan codes (`M3-08`, `C9`, `S4`…). The rewrite removes them.
- **Two real TODOs** (`AvroSchemaJsonDeserializer.kt:203`, `RecordResolver.kt:225`): keep them, or turn them into
  issues.
- **Tests:** four times v2's volume. Some files exist mostly to pin internal mechanics (`DecoderReuseTest`,
  `PolymorphicDecoderReuseTest`, `RecordFieldStateDecodingTest`). They guard real correctness after reuse, so keep them,
  but trim them during the cleanup.

## 4. History rewrite plan

### 4.1 Constraints and approach

- 269 commits since `main`. About half are `docs(plans)`. There are 2 upstream merges and 12 unit-branch merges.
- The history also has churn. kotlinx-io came into core and was later removed. The Apache converter moved into core
  and back out. The varint reads were reworked 5 times. Apache-typed facades and test bridges were written and then
  deleted. Stream overloads were removed and then re-added.
- **Approach: rebuild, don't rebase.** Build a new branch from upstream `48b4f24f`, using the **final, cleaned tree** as
  the source of truth. Each commit takes its files from that tree (`git checkout <final> -- <paths>`, plus `-p` for
  partial files), so no churn and no throwaway code reach the new history.
- The last commit's tree must equal the final tree, except `docs/plans/`, `CLAUDE.md` and the trimmed benchmark
  README. `git diff` checks it.
- Cherry-picking the old commits would keep the churn, the verbose comments and the plan codes in every intermediate
  commit.

### 4.2 Before the rewrite (normal commits on `v3/m2-foundation`, nothing rewritten)

1. **Merge `avro-kotlin/main` (`48b4f24f`).** Port #559 and reconcile #563 (§ top). Run ACC and ACC+KMP, and check the
   API dumps.
2. **Code-quality pass** (§3): comments, the `AvroDecoder` → decoder-internals coupling, and the second look at the
   byte-saving tricks (allocation-only check, on AC). You review it before going further.
3. **Freeze:** full verification, then tag the result `v3-final-tree`.
4. **Archive:** tag the current tip `archive/v3-lab-history` and push it. This keeps the notes, the ledger and every SHA
   cited in the benchmark README resolvable.

### 4.3 Target series (on `48b4f24f`, about 40 commits)

Commits are grouped into **checkpoints**. Every checkpoint builds and passes ACC (and ACC+KMP from checkpoint 6).
Every commit outside checkpoint 4 also builds on its own. Inside checkpoint 4 (the type switch), commits are split by
area for review but only the last one builds: splitting it into building commits would need throwaway facades.

**CP1 — Groundwork**
1. `build(core): core as a Kotlin Multiplatform module, JVM target only`. `src/main` → `src/jvmMain` and `src/test` →
   `src/jvmTest` as **pure renames**, plus the multiplatform convention in `build-logic`.
2. `fix(core): copy decoded bytes out of the caller's ByteBuffer` (`2f062c60`, on v2 code).
3. `fix(core): map values report their own schema in the generic decoder` (`8001e573`).
   2 and 3 can also go to a 2.x patch release.

**CP2 — The schema model (additive; v2 runtime untouched)**
4. `refactor: move the AvroSchema draft from kotlin-generator to core` (pure renames).
5. `feat(core): AvroSerializationException and its families` (public classes, not used yet).
6. `feat(core): the multiplatform AvroSchema model` (+ tests).
7. `feat(core): parse and write AvroSchema as JSON` (+ tests).
8. `feat(core): parsing canonical form and CRC-64-AVRO in common code` (+ tests against Apache).
9. `feat(apache-interop): new module, conversions with Apache's Schema`. kotlin-generator uses it.

**CP3 — Platform-neutral internals (additive, tested alone, not used yet)**

10. `feat(core): multiplatform weak and identity caches` (JVM actuals).
11. `feat(core): binary codec: varint, UTF-8, ByteArray encoder and decoder, JVM stream and ByteBuffer decoders`.
12. `feat(core): validating codec and writer-schema call validator`.

**CP4 — Runtime and public API on AvroSchema (one build at the end)**

13. `refactor(core)!: schema generation produces AvroSchema` (visitors, `VisitorContext`, `AvroSchemaHelpers`, the
    `createSchema` of annotations).
14. `refactor(core): record resolution and default values without GenericData`.
15. `refactor(core): decoders on AvroSchema and the own codec` (**pre-perf version**, see CP8).
16. `refactor(core): encoders on AvroSchema and the own codec` (pre-perf version).
17. `refactor(core)!: built-in serializers on AvroSchema` (common, JVM, `AnySerializer` split).
18. `refactor(core)!: public API on AvroSchema`. Covers `Avro`, `AvroDecoder`/`AvroEncoder` (`ResolvedSchema`,
    `avro`), `AvroSingleObject` and JVM streams. Removes kotlinx-io/okio, `Record`/`ListRecord`, `decodeValue` and
    `decodeResolving*`.
19. `refactor!: GenericData, AvroObjectContainer and the Apache codecs move to apache-interop` (renames + changes).
    Includes Confluent's bridge.
20. `build(core)!: core without Apache Avro, Jackson, okio and kotlinx-io`.
21. `test(core): tests on AvroSchema` (mechanical migration), plus the benchmark module adapted to compile.
    **Green.**

**CP5 — Behaviour changes, one commit each (green each)**

22. `feat(core)!: fixed types named after their value class or record` (`2b3ac708`).
23. `fix(core)!: default values per the Avro 1.12 spec` (`7e669eb5`, `f38b2377`, union branch order).
24. `feat(core)!: uuid decodes to kotlin.uuid.Uuid` (`50c17fde`).
25. `fix(apache-interop)!: generic decoding resolves unions from the value` (`ef9133e6`, `66d33327`).
26. `fix(core)!: malformed input always fails with AvroMalformedDataException` (`dc20fa6c`).
27. `fix(core): six defects` (`ccc6c407`) and the alias fix (`92c87cbe`), as separate commits.

How to build these: try `git apply -R` of the original commit's diff on the final files. If it applies, CP4 takes the
"before" files, and the CP5 commit re-applies the change with its tests. If it does not apply, the change stays inside
its CP4 commit and is listed in that commit's message. I expect 22, 24 and 26 to apply; 23 and 25 are less certain.

**CP6 — Kotlin Multiplatform (green each, ACC+KMP)**

28. `refactor(core): platform-free code to commonMain` (pure renames from `jvmMain`).
29. `build(core): JS and native targets`. Adds the platform actuals, `kotlin-js-store`, Maven `-jvm` publication and the
    klib ABI dump.
30. `test(core): tests to commonTest, and cross-platform golden fixtures checked against Apache`.

**CP7 — Generator and plugin**

31. `feat(kotlin-generator)!: fixed types as value classes, uuid as kotlin.uuid.Uuid`.
32. `feat(kotlin-generator, gradle-plugin): opt-ins of logical types and file annotations`.

**CP8 — Performance (green each; each message carries its measured delta)**

33–42. Replays the unit commits on top of the pre-perf decoders and encoders from CP4:

| Change | Source |
|---|---|
| One merge per single-block collection | `v3/collection-single-merge` |
| Strings encoded in place | `v3/string-write-in-place` |
| Decoders reused per level | `v3/decoder-reuse` |
| Encoders reused per level | `v3/encoder-reuse` |
| Exact records decoded in order | `v3/exact-record-sequential` |
| Union tables and small encode methods | `v3/union-tables-small-encode` |
| Polymorphic decode reuse | `v3/polymorphic-decode-reuse` |
| Writer schema field and nullable path | `v3/schema-field-nullable-path` |
| Nested and reordered record reads | `v3/nested-record-read` |
| JS long writes | `v3/js-long-write` |

Conflicts will be in comments only. Resolve them to the cleaned text. The last commit is snapped to `v3-final-tree`
for those files, and its diff must show only comments.

**CP9 — Benchmarks and docs**

43. `perf(benchmark): JMH suite on AvroSchema, v2/v3 configuration, power and allocation guards, benchmark-common`.
44. `docs: README for multiplatform, Migrating-from-v2.md, gradle-plugin README; benchmark results trimmed to v2 vs v3
    vs Apache`.

Left out of the new history: `docs/plans/`, `CLAUDE.md` and the benchmark lab journal. They stay on
`archive/v3-lab-history`.

### 4.4 Checks

- At each checkpoint: `./gradlew actionsBeforeCommit`, ACC+KMP from CP6, and `apiDump && git diff --exit-code` from
  CP4.
- At the end: `git diff v3-final-tree <new tip> -- . ':!docs/plans' ':!CLAUDE.md' ':!benchmark/README.md'` is empty.
- No plan codes in any commit message or file: grep the series.
- Size: every commit except 21 (the mechanical test migration) stays under about 1,500 changed lines of production
  code. List the outliers with `git log --shortstat`.
- No benchmark is needed: the final tree is the measured one. Commit SHAs in the trimmed README point to the archive
  tag.

### 4.5 Decision for you

**How the PR lands on `main`.** v2's rework (#258) was merged with its history kept.
- **Recommended:** review commit by commit on the series above, then merge with history kept, after squashing CP4's
  nine commits into one so every commit on `main` builds.
- **Alternative:** keep CP4 split and accept that `git bisect skip` is needed there.
