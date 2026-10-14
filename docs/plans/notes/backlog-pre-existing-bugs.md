# Backlog — bugs and spec gaps that predate the v3 migration

**Rule (user, 2026-10-13, "very important"):** a missing or wrong behaviour, or a bug, that already existed before the
migration (incorrect spec implementation included) is **not fixed during the migration**, unless the pure Kotlin code
written for multiplatform support fixes it as a side effect. It is **written down here** so the user can handle it
separately: in parallel with the migration, or after it. New cross-platform divergences (a platform behaving
differently from the JVM) are migration scope, not this backlog.

Each entry below was reproduced on `main` (`56dc7da8`, the pre-migration JVM code) with a throwaway test on
2026-10-13, and found by M3-16's golden fixtures (`notes/m3-16.md`). The fixtures avoid each case with a comment.
"Issue" = text ready to paste into a GitHub issue; the user creates them.

---

## 1. A non-nullable record property cannot read a writer union

**Repro (main and v3):** writer `{"type":"record","name":"ProbeHolder","fields":[{"name":"value","type":["null","int"]}]}`,
bytes `02d804` (branch 1, 300), reader `data class ProbeHolder(val value: Int)` →
`SerializationException: Unsupported schema named 'union[null, int]' for decoded type of INT, and also not matching to
any compatible type (one of LONG, STRING)`. Same with `["long","int"]`, and for every primitive and `String` property
(`String?` → `String` fails the same way). Re-checked on both branches on 2026-10-13, what works: a nullable property
(`Int?` → `Int?`), and a non-nullable **enum, record or list** property read from `["null", T]` (`Enum?` → `Enum`,
`Record?` → `Record`, `List?` → `List` all decode). Decoding the union as a whole value works too
(`Avro.decodeFromByteArray(["null","int"], Int.serializer(), …)` → 300). Typical trigger: a producer's model had
`val value: Int?` and the consumer's has `val value: Int`; every value written was non-null, yet none can be read.

**Spec:** writer union, reader not a union: the branch the writer selected is resolved against the reader's schema; an
error only if it does not match. So this is valid input.

**Cause:** the direct decoder resolves a union (reads its index) only in `decodeSerializableValue` and
`decodeNotNullMark`. A nullable property goes through `decodeNotNullMark` first, and an enum, record or list property
through `decodeSerializableValue`, so they work. A primitive record element goes through kotlinx's `decodeIntElement` →
`decodeInt()` (likewise `decodeString`, `decodeLong`, …), which switch on the raw writer schema (`currentAvroSchema` on v3, the `var
currentWriterSchema` on `main`), still the union. On v3 the public `currentWriterSchema` getter does resolve the union
(since M3-24), but the decoder's own `decode*` methods read the raw field.

**Fix sketch:** read the resolved schema at the start of each primitive `decode*` of `AbstractAvroDirectDecoder` (on v3:
use `currentWriterSchema` instead of `currentAvroSchema`, or call `decodeAndResolveUnion()` first). Cost: one type
compare per decoded value on the hot path → A/B on `complex` read. Tests: a record property of each primitive type and
`String` (and a value class over one), read from `["null", T]` and from `[T, other]`.

**Issue:** *Decoding fails when a non-nullable primitive or String property is written as a union* — a reader
`data class X(val value: Int)` cannot read bytes written with `"value": ["null","int"]` (or any union), e.g. written by
the same class when the property was still `Int?`, although the Avro spec resolves the written branch against the
reader's type. Nullable properties, non-nullable enum/record/list properties and top-level values work.

## 2. No `int`/`long` → `float`/`double` promotion at decoding

**Repro (main and v3):** `Avro.decodeFromByteArray(Schema.create(INT), Float.serializer(), byteArrayOf(2))` →
`Unsupported schema named 'int' for decoded type of FLOAT, and also not matching to any compatible type (one of DOUBLE,
STRING)`. Same for int → double, long → float, long → double, at root and in records.

**Spec:** "int is promotable to long, float, or double; long is promotable to float or double". Supported today:
int → long, float → double, string ↔ bytes. Conversely avro4k accepts double → float (with an exactness check), which
the spec does not list.

**Fix sketch:** `decodeFloat`: `INT -> readInt().toFloat()`, `LONG -> readLong().toFloat()`; `decodeDouble`: the same
with `toDouble()` (Apache casts: `16_777_217` → `16_777_216f`, which the first version of the promotions fixture checked
against Apache). Also the generic decoder and `JsonDefaultDecoder` if they have the same switch. Tests per promotion.

**Issue:** *Missing numeric promotions when decoding: int/long to float/double* — the spec's promotions int → float,
int → double, long → float, long → double fail with `Unsupported schema named 'int' for decoded type of FLOAT`.

## 3. Two classes with the same serial name can get each other's schema — revisit after the migration

**Repro (main and v3):** `@SerialName("probe.Palette") data class PaletteA(val known: ColorA)` and
`@SerialName("probe.Palette") data class PaletteB(val known: ColorB)`, both enums `@SerialName("probe.Color")` with
different symbols. `Avro.schema(PaletteA)` then `Avro.schema(PaletteB)` → B's schema has **A's** symbols.

**Cause:** kotlinx's descriptor `equals` compares the serial name and each element descriptor's serial name and kind;
not element names, not annotations, not enum symbols of nested descriptors. `Avro.schemaCache` is backed by descriptor
equality (plain `ConcurrentHashMap` on `main`; identity first then equality on v3, M3-06). The equality layer exists for
descriptors created per call (generic types), which must hit the cache.

**When it bites:** one `Avro` instance, two shapes under one serial name: schema-migration code (V1/V2 classes of the same
record), tests. Silent: a wrong schema, no error.

**To decide later (user: after the migration):** keep the equality layer for descriptors and key it on something that
includes element names, annotations and nested enum symbols; or drop equality for descriptors and accept a miss per
fresh generic descriptor; or document it. Not an issue yet.

## 4. `AvroDuration`'s fractional seconds are not ISO-8601

**Repro (main and v3):** `AvroDuration.parse("PT4.5S").millis` → `4005` (ISO-8601 and `java.time.Duration`: 4,500);
`AvroDuration.parse("PT0.5S").millis` → `5` (500); `AvroDuration(0u, 0u, 4005u).toString()` → `PT4.5S` (`PT4.005S`).

**Cause:** `toString` appends `millis % 1000` without padding it to three digits; `parse` turns the digits after the
point into a number of milliseconds without scaling them by their count. The two mistakes cancel each other, so avro4k
reads back what it wrote and its tests pass; a duration from any other producer, or a string-encoded duration
(`@AvroStringable`, default values), is misread by up to ×100.

**Fix sketch:** `toString`: `padStart(3, '0')` on the millisecond part (and may drop trailing zeros: `PT4.5S` for
4,500 ms). `parse`: scale the fraction (`"5"` → 500, `"05"` → 50, `"005"` → 5). Tests with other producers' strings.
**Migration note:** anyone who stored avro4k's own one- or two-digit fractions (`PT4.5S` meaning 4,005 ms) reads a
different value after the fix.

**Issue:** *AvroDuration parses and prints fractional seconds wrongly* — `AvroDuration.parse("PT0.5S")` gives 5 ms
instead of 500 ms, and a duration of 4,005 ms prints as `PT4.5S` instead of `PT4.005S`.

## 5. A relative `@AvroAlias` on an enum is not resolved in the enum's namespace at decoding

**Repro (main and v3):** writer enum `{"type":"enum","name":"Color","namespace":"probe",…}`, reader
`@SerialName("probe.Colour") @AvroAlias("Color") enum class Colour` → `Unsupported schema named 'probe.Color' for
decoded type of ENUM`. With `@AvroAlias("probe.Color")` it works. Schema generation does resolve the relative alias
(`AvroAlias`'s KDoc: "A type's alias without a namespace is resolved in the type's namespace"), and so does Apache's
resolution.

**Cause:** the decoder compares the writer's full name with the raw `@AvroAlias` values (`SerialDescriptor.aliases`),
not with the aliases qualified by the descriptor's namespace. Records may have the same gap but are not name-checked at
decoding, so it does not show there; fixed types to be checked.

**Fix sketch:** qualify relative aliases with the descriptor's namespace before matching (the schema-generation code
already does it), cached per descriptor. Tests for enum (and fixed).

**Issue:** *Relative @AvroAlias on an enum does not match at decoding* — `@AvroAlias("Old")` on an enum in namespace `x`
does not match a writer enum `x.Old`, although the generated schema resolves the alias to `x.Old`.

## 6. The README calls `@AvroStringable` by an old name

**Found 2026-10-13** while documenting the Kotlin/JS floating-point text (on `main` `56dc7da8` too): the README's section
"Force a field to be a `string` type" names the annotation `@AvroString` 5 times (text and examples), which does not
exist; the annotation is `@AvroStringable` (`@ExperimentalAvro4kApi`). **Fix:** rename in the README, mention the opt-in.
Docs only, no issue needed.

---

Entries 7–11 were reported by M3-17's review (`notes/m3-17.md`) as code older than the migration, and reproduced on
`main` (`6ad6c1c6`) with throwaway tests on 2026-10-14 (deleted afterwards). "v3" = the same code is on
`v3/m2-foundation`, checked by reading it.

## 7. `NaN` cannot be written to or read from a `float`

**Repro (main, v3):** `Avro.encodeToByteArray(Schema.create(FLOAT), Double.serializer(), Double.NaN)` →
`SerializationException: Value NaN is not a valid Float`; same when decoding a `double` NaN into a `Float` property.
`Double.POSITIVE_INFINITY` and finite values that fit work.

**Cause:** `Double.toFloatExact()` (`NumberUtils.kt`) checks `toFloat().toDouble() != this`, which is always true for
NaN (`NaN != NaN`).

**Fix sketch:** `if (isNaN()) return Float.NaN` before the check (also in apache-interop's copy). Tests both ways.

**Issue:** *Double.NaN is rejected when the schema is float* — writing `Double.NaN` with a `float` writer schema, or
reading a `double` NaN into a `Float`, fails with "Value NaN is not a valid Float".

## 8. A `String` written to a union of fixed types picks the branch by its char count

**Repro (main, v3):** union `[{"type":"fixed","name":"F1","size":1},{"type":"fixed","name":"F2","size":2}]`, value
`"é"` (1 char, 2 UTF-8 bytes) → `Fixed size mismatch for actual size of 2. Actual schema: …"F1"…`; `F2` fits.

**Cause:** `AbstractAvroEncoder.encodeString` calls `trySelectFixedSchemaForSize(value.length)` (UTF-16 chars), while
the value is then written as its UTF-8 bytes.

**Fix sketch:** select with the UTF-8 byte count (computed once, reused for the write). Only non-ASCII strings change.

**Issue:** *Non-ASCII string written to a union of fixed types picks the wrong branch*.

## 9. Decoding generic data reads a whole `ByteBuffer`'s array, ignoring its position and limit

**Repro (main; v3 in apache-interop):** `Avro.decodeFromGenericData(Schema.create(BYTES), ByteArraySerializer(),
ByteBuffer.wrap(byteArrayOf(9, 1, 2, 9), 1, 2))` → `[9, 1, 2, 9]` instead of `[1, 2]`. A direct or read-only buffer
throws instead. Apache's own readers hand exact heap buffers, so decoding straight from them is not affected; a buffer
built or sliced by the caller is.

**Cause:** `AbstractAvroGenericDecoder.decodeBytes`: `is ByteBuffer -> value.array()`.

**Fix sketch:** copy `remaining()` bytes from a `duplicate()` (leaves the caller's position alone). Same for fixed
values given as a `ByteBuffer`, if accepted there.

**Issue:** *decodeFromGenericData ignores a ByteBuffer's position and limit*.

## 10. A custom serializer that delegates to `ListSerializer` fails on the next field

**Repro (main, v3):** record `{a: array<int>, b: array<int>}` written from `Lists(listOf(1, 2), listOf(3))`, read with
two properties using `object WrapperSerializer : KSerializer<Wrapper> { deserialize = Wrapper(ListSerializer(Int.serializer()).deserialize(decoder)) }`
→ `SerializationException: Not all bytes were consumed during deserialization`.

**Cause:** collection blocks are read by avro4k's collection decoding, entered through `decodeSerializableValue` on a
list serializer; a serializer calling the list serializer's `deserialize` directly reaches the decoder's
`beginStructure` without it, so the block terminator is not consumed and the next field starts out of step.

**Fix sketch:** handle the blocks in `beginStructure`/`endStructure` for list and map descriptors whatever the entry
point, and test a delegating
serializer for list and map, in one and several blocks.

**Issue:** *Custom serializer delegating to ListSerializer.deserialize corrupts decoding* — a common pattern for
wrapper types.

## 11. kotlin-generator writes a `$` of the schema into a Kotlin string template

**Repro (main, v3):** `.avsc` record with `"doc":"costs $price"` → generated `@AvroGenerated("""{…"doc":"costs $price",…}""")`;
`$price` is a string template, so the generated file does not compile (or silently embeds a variable's value). The KDoc
and `@AvroDoc("costs ${'$'}price")` are escaped correctly. Any doc, default or prop containing `$` triggers it.

**Cause:** `AnnotationsHelper.buildAvroGeneratedAnnotation` adds the schema JSON with KotlinPoet's `%P` (string
template format), which does not escape `$`.

**Fix sketch:** `%S`, or escape `$` as `${'$'}` before `%P`; golden test with a `$` in a doc, a default and a prop.

**Issue:** *Generated code does not compile when the schema contains a dollar sign*.

## 12. A failed root-string or root-bytes read poisons the next read on the same thread (confluent)

**Repro (main, v3; re-checked on `main` `6ad6c1c6` on 2026-10-15):** `SpecificAvro4kKafkaDeserializer<Rec>` (`Rec(val value: String)`), registry mock. First
`deserialize` a truncated payload of a root `string` message (`GenericAvro4kKafkaSerializer().serialize("t", "hello")` minus its last 3 bytes)
→ `SerializationException`. Then `deserialize` a valid `Rec` payload on the same thread → `ClassCastException: Rec cannot be cast to String`.

**Cause:** `getDatumReader` stores the writer schema in the deserializer's `ThreadLocal` for a root STRING/BYTES schema,
and only `deserialize` clears it, after `super.deserialize` returned. When that throws, the stale schema stays and the next
message of the thread is wrapped as if it were a root string. The code is identical on `main`.

**Fix sketch:** clear the `ThreadLocal` in a `try/finally` around `super.deserialize` (or read and clear it before calling it); test a failed read followed by a valid one.

**Issue:** *Specific deserializer throws ClassCastException after a failed root string/bytes read* — a bad record then
breaks every following message of that consumer thread.
