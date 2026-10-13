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
any compatible type (one of LONG, STRING)`. Same with `["long","int"]`, and for every primitive, `String` and enum
property. Decoding the union as a whole value works (`Avro.decodeFromByteArray(["null","int"], Int.serializer(), …)` →
300), and so does a nullable property.

**Spec:** writer union, reader not a union: the branch the writer selected is resolved against the reader's schema; an
error only if it does not match. So this is valid input.

**Cause:** the direct decoder resolves a union (reads its index) only in `decodeSerializableValue` and
`decodeNotNullMark`. A primitive record element goes through kotlinx's `decodeIntElement` → `decodeInt()` (likewise
`decodeString`, `decodeEnum`, …), which switch on the raw writer schema (`currentAvroSchema` on v3, the `var
currentWriterSchema` on `main`), still the union. On v3 the public `currentWriterSchema` getter does resolve the union
(since M3-24), but the decoder's own `decode*` methods read the raw field.

**Fix sketch:** read the resolved schema at the start of each primitive `decode*` of `AbstractAvroDirectDecoder` (on v3:
use `currentWriterSchema` instead of `currentAvroSchema`, or call `decodeAndResolveUnion()` first). Cost: one type
compare per decoded value on the hot path → A/B on `complex` read. Tests: a record property of each primitive type,
`String`, enum, read from `["null", T]` and from `[T, other]`.

**Issue:** *Decoding fails when a non-nullable property is written as a union* — a reader `data class X(val value: Int)`
cannot read bytes written with `"value": ["null","int"]` (or any union), although the Avro spec resolves the written
branch against the reader's type. Nullable properties and top-level values work.

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
