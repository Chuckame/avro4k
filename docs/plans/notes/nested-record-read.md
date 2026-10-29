# Nested and reordered record reads: win back what the field and nullable steps cost them

**Outcome:** two fixes, one commit each, each passing `ACC` alone, the more conservative first: fix 1 for the reordered
records, fix 2 for the nested ones. Not measured: the orchestrator measures each commit as its own side (fix 1 against
the base, fix 2 against fix 1). Internal code only: no API change (`core/api`, klib included, and `apache-interop/api`
are clean).

**Branch** `v3/nested-record-read`, base `4b7ea253`.

## What was measured, and the question

From `benchmark/README.md`, "Results — reads with decoders reused, records in order, polymorphic values and nullable
fields": `NestedRecord.read` depth 1 / 3 / 8 lost 7.6% / 10.3% / 12.1% at the nullable step (`4f05b518`) although its
records have no nullable field, and `FieldOrder.read` reversed lost 10.8% at the field step (`5e174c72`). Why, and
what to change so that neither pays for a step that does nothing for it.

## How it was looked at

No benchmark was run. A plain JVM loop (JBR 21.0.11, the benchmarks' JDK) calls the micros' own `read()` 3 million
times, with `-XX:+PrintCompilation -XX:+PrintInlining -XX:-BackgroundCompilation` (the last so that each C2 inlining
tree follows its compilation's header), against core jars built in this worktree: the polymorphic step (`cbb390b2`'s
decoders: `4b7ea253` with the field and nullable diffs of `core/src/commonMain` reverted), the field step (only the
nullable diff reverted), the base `4b7ea253`, and each fix. It also counts the bytes the thread allocates over 1 million
calls after the loop, escape analysis on: a diagnostic of which allocations C2 removes, not a B/op row. No timing comes
from it.

## Why the nested records paid for the nullable step

**Not an inlining threshold, nor a megamorphic site.** The C2 inlining trees of the nested workload at depth 1, 3 and 8
are the same at the field step and at the nullable step, but for the calls the nullable step added:

- `beginStructure` (313 B) is never inlined into the deserializers (`already compiled into a big method`), nor
  `beginRecord` into `beginStructure`; both are their own compilations on every side. In `beginRecord`'s, the
  constructor of the exact decoder and its `reset` are inlined on every side (`inline (hot)`).
- The constructor (31 → 38 B) and `reset(4)` (38 → 51 B) crossed `MaxInlineSize` (35 B), which only matters at a cold
  call site: their call site in `beginRecord` is hot. Only C1 noticed (the constructor, `callee is too large` in the
  tier-3 code, got a C2 compilation of its own at the nullable step), which steady state does not run.
- The one megamorphic site, `deserialize` called from `decodeSerializableValue` (8 serializer classes), and the
  recursion stop (`recursive inlining is too deep` at the second level) are the same on every side.
- Escape analysis: at depth 1, a run either removes both decoders (48 B allocated per call) or none (176 B), at random
  and on every side; at depth 3 and 8, never (352 / 792 B, as measured with escape analysis off). Not the step's doing,
  but the depth-1 row is bimodal across forks.

**It is per-record work, done for every record of the nested micro.** Each level's decoder hangs off the decoder of the
level above, which the same call created: every record of every call is begun on a new decoder, which resolves its
record type (memo miss) and so runs the constructor and `reset(4)`. The nullable step added, per such record:

- in the constructor (31 → 38 B): a read of the static `NO_NULLABLE_UNION_FIELDS` and a write of `nullableFields`, its
  initializer, only to be overwritten by `reset(4)` a few instructions later;
- in `beginRecord`: a read of `workflow.exactNullableFields`;
- in `reset(4)`: a write of `nullableFields`.

Records without a field written as `["null", T]` need none of it. The decoder of the root of a call is new too, so every
call to `decode` pays it once, whatever its records. The same pattern was there before the nullable step for every other
field: a new decoder writes its fields' defaults in its constructor (`fieldSchemas`, `nextElementIndex`, the base
class's `currentAvroSchema`), and `reset` writes them again at once.

## Why the reversed order paid for the field step

The general record decoder (fields reordered, skipped or defaulted) writes, per field, in `decodeElementIndex`:

- before the field step, one reference: `decodingStep = field`; the value's schema was read back through the overridden
  `currentAvroSchema` getter (`decodingStep.schema`), inlined here (a single decoder kind in this micro);
- since the field step, two references: `decodingStep = field` and `currentAvroSchema = field.schema`.

Each reference write into the heap carries G1's barriers (the marking check, then the card check, which does not exit
early: the decoder is young, the steps and schemas it points to are not). The field step thus added one barriered write
per field and saved this micro nothing (its getter was already inlined). `decodingStep` only served to tell a field read
from the input from a field taking its default value (and to fail before any field is begun, which the field step's
marker already does). The inlining trees of the `FieldOrderRecord` deserializer are otherwise identical at the two steps
(everything inlined into it on both). `swapped` takes the same per-field path and pays the same.

## Fix 1 (conservative): no reference written per field for its kind

`RecordDirectDecoder.kt`, the general record decoder only. `decodingStep` (a reference) is replaced by
`stepAfterDefaultValue` (an `Int`): 0 (`READ_FROM_INPUT`) while the field being decoded is read from the input, else the
index of the step after its `GetDefaultValue`. `decodeElementIndex` writes it per field as a plain int; each `decode*`
override compares it with 0 instead of type-checking the step, and finds the `GetDefaultValue` by index only for a
default value. 0 rather than -1 so that a new decoder starts with it without a write (Kotlin emits no initializer for
0: the constructor stays 31 B).

Before any field is begun, every read now fails through the base class's marker of no field begun, with the same
exception and message (`noElementBegunError`) and without reading the input, as `currentDecodingStep` failed before;
after the last field, and after `endStructure`, a read decodes as the last field returned, as before.

**Tried and dropped: no nullable state in the exact decoder's constructor.** Making `nullableFields` nullable with no
initializer (null for records without such fields, `NO_NULLABLE_UNION_FIELDS` removed) made the nested workload worse in
the inlining logs: `reset(4)` and the `exactNullableFields` accessor were no longer inlined into `beginRecord`, with
`unloaded signature classes`. C2 does not inline a method whose signature names a class not loaded yet, and without the
shared empty array, `NullableUnionField` is never loaded by a workload that has no nullable field. The shared empty
array, created when `RecordResolver.kt` is first used, is what loads it at the base: it stays, and fix 2 says why in the
code.

### Bytecode (`javap -c -p`, code length, as the JIT logs print it)

| Method | Before (`4b7ea253`) | Fix 1 |
|---|---:|---:|
| `RecordDirectDecoder.decodeElementIndex` | 204 | 201 |
| `RecordDirectDecoder.decodeInt` / `decodeLong` / `decodeBoolean` / `decodeChar` / `decodeString` / `decodeDouble` / `decodeFloat` / `decodeBytes` / `decodeFixed` | 49 each | 25 each |
| `RecordDirectDecoder.decodeNotNullMark` | 56 | 36 |
| `RecordDirectDecoder.decodeNull` | 62 | 40 |
| `RecordDirectDecoder.decodeSerializableValue` / `decodeEnum` | 57 / 57 | 33 / 33 |
| `RecordDirectDecoder.getCurrentDecodingStep` | 14 | — (removed) |
| `RecordDirectDecoder.defaultValueStep` (new, rare path) | — | 18 |
| `RecordDirectDecoder.defaultDecoder` (rare path) | 20 | 26 |
| `RecordDirectDecoder.<init>` / `reset()` / `reset(3)` | 31 / 23 / 38 | 31 / 23 / 38 |

The `decode*` overrides fall from 49 B to 25 B (the type switch on the step is gone), below `MaxInlineSize`. In the
reversed workload's inlining log, the `FieldOrderRecord` deserializer inlines the same methods as at the base, minus
`getCurrentDecodingStep`.

### Layout

Unchanged: the general decoder trades a 4-byte reference for a 4-byte int.

## Tests added with fix 1 (4, `commonTest`, run on JVM, JS, native)

`core/src/commonTest/kotlin/com/github/avrokotlin/avro4k/encoding/RecordFieldStateDecodingTest.kt` (new), each with and
without validation:

1. `reordered fields read from the input and from their defaults decode every kind of value`: a reordered record with a
   skipped writer field, five fields read from the input (int, string, boolean, double, nullable long, null and not)
   and twelve taken from their default (int, long, string, boolean, double, float, char, enum, null, non-null nullable,
   list, record), three in a list, so each record after the first is begun on the decoder that ended one on a default.
2. `general records of two types alternating on one decoder read each field from the right place`: one with defaults,
   one without, alternating as siblings (the same kept decoder resolved anew for each).
3. `every read on a general record decoder fails before any field is begun also when reused after a default value`:
   17 reads (`currentWriterSchema`, `decodeNotNullMark`, `decodeNull`, each primitive, `decodeString`, `decodeEnum`,
   `decodeBytes`, `decodeFixed`, `decodeSerializableValue`, `decodeNullableSerializableValue`) each fail with
   `IllegalStateException` and the no-field message, then the record decodes, its last field from a default; three
   records in a list.
4. `exact records with and without nullable fields alternating on one decoder and nested decode`: records with and
   without `["null", T]` fields alternating as siblings, nested three deep with a nullable record field, in a list (the
   exact decoder's nullable fields are set per record type, which fix 2 moves into its constructor for a new decoder).

All four pass on `4b7ea253` too (checked on the JVM before the change). Test 3 also fails when the general decoder's
`reset` stops clearing the default-value index (checked by removing it: test 3 and `DecoderReuseTest`'s
`a reused decoder knows no field or item before one is begun` fail; restored).

### Test counts

| Task | Before (`4b7ea253`) | After fix 1 |
|---|---:|---:|
| `:core:jvmTest` | 1175 | 1179 |
| `:core:jsNodeTest` | 332 | 336 |
| `:core:macosArm64Test` | 335 | 339 |

### Acceptance of fix 1

Both `ACC` invocations: BUILD SUCCESSFUL; the API dumps of `core` and `apache-interop` unchanged (`git diff
--exit-code`).

## Predictions of fix 1, against the base `4b7ea253`

**Allocation, escape analysis off: no change on any row** (same layout, nothing allocated where nothing was).

**Throughput:**
- `FieldOrder.read` swapped and reversed: up, per field one barriered reference write fewer and an int compare instead
  of a type check per value, back towards the polymorphic step's numbers or above.
- Must not move: `NestedRecord.read` (exact records only, untouched), `FieldOrder.read` matching, `simple`, `complex`,
  `StringField.*`, `Collection.*`, `Polymorphic.*` (records decoded exactly as written; the general decoder runs only
  where fields are reordered, skipped or defaulted).
- Every write row: unchanged.
