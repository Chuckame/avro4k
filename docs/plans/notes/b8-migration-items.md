# B8 — migration guide items (collection point)

The v3 migration guide is written in B8. This file collects the items as the units find them, so that B8 starts from
a list instead of a search. Each item says what changed, who is affected, and what to do. The detailed sections come
first; the "Collected so far" list at the end points to the items recorded elsewhere.

## Fixed type names

**User decision, 2026-09-25** (see the `PROGRESS.md` decision log, and [`m3-09-fixed-naming.md`](m3-09-fixed-naming.md)
for the implementation).

### What changed

A fixed type is created by `@AvroFixed` (on a `ByteArray`, a `String`, a `BigDecimal` with `@AvroDecimal`, a
`java.util.UUID` or a `kotlin.uuid.Uuid`). Its full name used to be the annotated property's name, in the namespace of
the class that declares the property (the package part of its serial name). It now depends on where `@AvroFixed` is:

| `@AvroFixed` is on | v2 full name | v3 full name |
|---|---|---|
| a **value class's property**, `@JvmInline value class Hash(@AvroFixed(4) val bytes: ByteArray)` in package `com.example` | `com.example.bytes` (the property's name) | `com.example.Hash`: the value class's **serial name**, so `@SerialName` sets it. A serial name without a dot means no namespace, as for records. |
| a **data class property**, `@AvroFixed(4) val id: ByteArray` in `data class A` in package `ns` | `ns.id` | `ns.A.id`: the property's name, in the **record's full name** as namespace |

On top of the name:

- `@AvroAlias` and `@AvroDoc` on the value class are now the fixed's aliases and doc. They used to be ignored on value
  classes. An alias without a dot is in the fixed's namespace, as for records.
- When both are present (a data class property annotated with `@AvroFixed` whose type is such a value class), the data
  class property's annotation wins, as before, and so does its naming (`<record full name>.<property>`).
- The `fixed(12)` schemas of `java.time.Duration`, `java.time.Period`, `kotlin.time.Duration` and `AvroDuration` are
  **not** created from `@AvroFixed`, and keep their name.
- Two different fixed types with one full name (e.g. two value classes with the same serial name and different sizes)
  fail the schema generation with `Can't redefine: <full name>. …`, the message Apache's `Schema.Parser` gives. Two
  records' fields can no longer clash: `ns.A.id` and `ns.B.id` are two names. (Before the rejection landed, the second
  fixed silently read as the first one in the schema's json.)

### Who is affected

Anyone whose generated schemas contain a fixed created by `@AvroFixed`: the schema's json, its full names and its
fingerprint change. Nothing changes for the durations, nor for a fixed schema returned by a custom `AvroSerializer`
that does not call `AvroFixed.createSchema`.

### Consequences (measured on Apache Avro Java 1.12.1)

- **avro4k's own data stays readable.** avro4k reads and writes a fixed by its size, not by its name: the encoder picks
  a union branch by size, the decoder reads by the writer schema's size. So data written by v2 is read by v3, and back.
- **Data-class-field fixed: only the namespace changes, which schema resolution accepts.** Apache Java and the schema
  registries compare the *unqualified* name of a named type, or the reader's aliases
  (`SchemaCompatibility.schemaNameEquals`). `ns.id` → `ns.A.id` is COMPATIBLE both ways, also as a union branch (pinned
  by `AvroFixedNamingSchemaTest`, "migration: a data class property's fixed only changes namespace…"). Registry
  compatibility checks pass.
  - But the full name and the schema fingerprint change: a registry registers a **new schema version**, and single
    object encoding (`AvroSingleObject`) writes a new fingerprint, which a reader that only knows the old schema cannot
    resolve.
  - Code generated from the schema by other tools (Avro's `SpecificCompiler`, avro4k's own `kotlin-generator`, …) moves
    the fixed class to another namespace, so another package.
  - An Apache `GenericDatumWriter` given a `GenericData.Fixed` built with the *old* schema selects a union branch by
    full name, so it no longer finds the branch (`UnresolvedUnionException`). `LogicalTypesEncodingTest` had to be
    updated for exactly that.
- **Value-class fixed: the simple name changes, which schema resolution rejects** unless the old full name is an alias
  of the new fixed: NAME_MISMATCH, or MISSING_UNION_BRANCH in a union (pinned by "migration: a value class's fixed gets a
  new simple name…"). A registry with BACKWARD / FORWARD / FULL compatibility refuses the new schema.

### Workaround: keep the old name, or alias it

To keep an exact old fixed name, wrap the field in a value class whose serial name is the old full name. To adopt the new
name but stay registry-compatible, add the old full name as an alias of the value class.

Before (v2), a data-class field:

```kotlin
package ns

@Serializable
data class A(@AvroFixed(4) val id: ByteArray)
```

```json
{"type":"record","name":"A","namespace":"ns","fields":[
  {"name":"id","type":{"type":"fixed","name":"id","size":4}}]}
```

After (v3), the same code generates `{"type":"fixed","name":"id","namespace":"ns.A","size":4}`. To keep `ns.id`:

```kotlin
package ns

@JvmInline
@Serializable
@SerialName("ns.id") // the v2 full name
value class Id(@AvroFixed(4) val bytes: ByteArray)

@Serializable
data class A(val id: Id)
```

```json
{"type":"record","name":"A","namespace":"ns","fields":[
  {"name":"id","type":{"type":"fixed","name":"id","size":4}}]}
```

Before (v2), a value class:

```kotlin
package ns

@JvmInline
@Serializable
value class Hash(@AvroFixed(4) val bytes: ByteArray) // fixed "ns.bytes" in v2, "ns.Hash" in v3
```

After (v3), staying compatible with the v2 schemas:

```kotlin
@JvmInline
@Serializable
@AvroAlias("ns.bytes") // the v2 full name: readers with the new schema still resolve the old one
value class Hash(@AvroFixed(4) val bytes: ByteArray)
```

```json
{"type":"fixed","name":"Hash","namespace":"ns","size":4,"aliases":["ns.bytes"]}
```

Or `@SerialName("ns.bytes")` to keep the old name exactly.

### kotlin-generator

The generator now generates a named fixed as a value class (`@SerialName("<fixed full name>")`, `@AvroAlias`,
`@AvroDoc`, and `@AvroFixed` on its `value` property) that fields, array items, map values and union branches reference,
instead of a `ByteArray` field annotated with `@AvroFixed`. A root fixed schema is generated as that value class, named
after the fixed, instead of a value class named after the file (`TestSchema`). Generated code that used the fixed
fields as `ByteArray` must now go through `.value`. The `duration` fixed is unchanged (see
[`m3-09-fixed-naming.md`](m3-09-fixed-naming.md)).

## Public API on `AvroSchema` (from [`m3-12.md`](m3-12.md), §4 — the `core.api` diff)

Every schema in core's public API is avro4k's own `AvroSchema`; names and parameter lists are unchanged. Apache users
convert with the `apache-interop` module: `apacheSchema.toAvro4k()`, `avroSchema.toApacheSchema()`.

| API | v2 | v3 |
|---|---|---|
| `Avro.schema(descriptor)`, `Avro.schema(serializer)`, `Avro.schema<T>()` | `Schema` | `AvroSchema` |
| `Avro.encodeToByteArray` / `decodeFromByteArray` with a `writerSchema` (+ reified) | `Schema` | `AvroSchema` |
| `encodeToSink` / `decodeFromSource` (kotlinx-io, okio), `encodeToStream` / `decodeFromStream`, `decodeFromByteBuffer` | `writerSchema: Schema` | `AvroSchema` |
| `AvroEncoder.currentWriterSchema`, `AvroDecoder.currentWriterSchema` | `Schema` | `AvroSchema` |
| `AvroDecoder.decodeFixed()` | `GenericFixed` | `ByteArray` (for a `GenericFixed`: `GenericData.Fixed(currentWriterSchema.toApacheSchema(), decodeFixed())`) |
| `decodeResolving*` resolver lambdas | `(Schema) -> …` | `(AvroSchema) -> …` |
| `AvroSerializer.getSchema(context)` and all built-in serializers | `Schema` | `AvroSchema` (from Apache: `toAvro4k()`) |
| `AvroStringable.createSchema()`, `AvroFixed.createSchema(elementLocation)` | `Schema` | `AvroSchema` |
| `AvroSingleObject(schemaRegistry)` lambda, `encodeToSink` / `encodeToStream` / `encodeToByteArray(writerSchema, …)` | `Schema` | `AvroSchema` |
| `AvroObjectContainer.openWriter(schema, …)` | `Schema` | `AvroSchema` |
| `MissingFieldsEncodingException(fields, writerSchema[, descriptor])` | `List<Schema.Field>, Schema` | `List<AvroSchema.RecordSchema.Field>, AvroSchema` |
| internal-API (`@InternalAvro4kApi`): `AnySerializer` hooks, `trySelectNamedSchema`, `namedSchemaNotFoundInUnionError`, `isNamedSchema` | `Schema`, `Schema.Type` | `AvroSchema`, `AvroSchema.Type` |

Behaviour to call out: `AvroSchema.toString()` is not json (`toJsonElement()`); `toApacheSchema()` keeps `char` and custom
logical types; an Apache schema equal to one already converted maps to the first one's conversion (docs/aliases of the
first win, as the v2 caches did); `MissingFieldsEncodingException` prints the model's json; built-in serializers ignore
unknown or invalid logical types on every schema; still Apache-typed: `encodeToGenericData` / `decodeFromGenericData`
(moved to `apache-interop` by M3-19), `AvroObjectContainerBuilder.codec`. (`ListRecord` and `Record` were removed by M3-19.)

## Collected so far

One line each, with where the details are. Not rewritten here.

- [`b0.md`](b0.md), "B8 / CI": Maven-only consumers must depend on `avro4k-core-jvm` (the root `avro4k-core` POM only
  carries the common dependencies); the JVM javadoc jar is now Dokka HTML; a full native publish needs a macOS runner.
- [`b0.md`](b0.md), "B8": enabling klib validation moves the `.api` dump to per-target files.
- [`m3-02.md`](m3-02.md): the `org.apache.avro.limits.*` system properties no longer apply to avro4k's own codecs;
  malformed negative/oversized lengths now fail with `SerializationException`. Also in the `PROGRESS.md` decision log
  (2026-09-24, M3-02), with the okio decoding fix.
- [`m3-04.md`](m3-04.md), "Behaviour changes": `logicalTypeName` returns `null` for a non-primitive `logicalType` prop;
  new `getFieldByName` and duplicate-field messages; `FixedSchema.size` is `Int`; `logicalTypeName` is a member of
  `AvroSchema`.
- [`m3-08.md`](m3-08.md), "For B8's migration guide": bytes/fixed defaults follow the spec's code points 0-255; defaults
  above `ÿ` rejected; wrong-length fixed defaults padded or truncated; a union default matching a later branch moves that
  branch first (new fingerprint); `@AvroDefault("null")` on a non-nullable string. Also in the `PROGRESS.md` decision log
  (2026-09-24, M3-08 follow-up).
- [`m3-09.md`](m3-09.md), "B8 migration guide": two different named types with one full name are rejected; name, prop
  and reserved-prop errors are `SerializationException`s; `Avro.schema()`'s default nodes are parser-typed; plus
  `AvroTypeException` for an invalid default, to change when Apache leaves core ("M3-13 / M3-14").
- This file, "Fixed type names".
- [`m3-12.md`](m3-12.md), §4: the public API on `AvroSchema` (section above), with its behaviour notes.
- [`m3-13.md`](m3-13.md), §2: an invalid `@AvroDefault` for its field type fails with a `SerializationException` (same
  message) instead of Apache's `AvroTypeException` (the item m3-09 left for later).
- [`m3-18.md`](m3-18.md): **`validateSerialization` failures are `kotlinx.serialization.SerializationException`s**
  (user, 2026-10-09: to become a subclass of the new `AvroSerializationException`, see `m3-decomposition.md` M3-22) instead of Apache's `AvroTypeException` (or the
  `ArrayIndexOutOfBoundsException` Apache threw for an out-of-range union index), with a new message naming the call,
  what was expected and where (e.g. ``Invalid call writeLong() at 'inner.l' for the writer schema: expected a value of
  type int``). The flag now works on every platform, also for the Apache codecs handed to
  `encodeWithApacheEncoder`/`decodeWithApacheDecoder` (Confluent, object container files), and it is **stricter than
  Apache's**: the encoded or decoded value must be complete, a call after its end is rejected, and collections count
  their items (one value per `startItem()` when encoding; every block read to its end when decoding).

## Removed or changed by the GitHub "V3" issues (user decisions, 2026-10-09)

- **#318:** the deprecated okio overloads (`Avro.encodeToSink`/`decodeFromSource` on `okio.BufferedSink`/`BufferedSource`,
  all three shapes of each) are removed; core no longer depends on okio, so it no longer comes transitively. Use the
  kotlinx-io overloads: `kotlinx-io-okio` bridges an okio stream (`source.asKotlinxIoRawSource().buffered()`,
  `sink.asKotlinxIoRawSink().buffered()`, then flush the sink). Behaviour change: the removed okio decoding overload
  consumed exactly the decoded bytes from the okio source; a buffered kotlinx-io source reads ahead, so decode consecutive
  values from the same kotlinx-io `Source` rather than re-wrapping the okio source each time.
- **#319:** the deprecated `Avro.encodeToStream`/`decodeFromStream` (all three shapes of each) and
  `AvroSingleObject.encodeToStream`/`decodeFromStream` are removed. Use the kotlinx-io overloads
  (`outputStream.asSink().buffered()` / `inputStream.asSource().buffered()` wrap a Java stream; flush or close the sink).
  Not removed (never deprecated): `Avro.decodeFromByteBuffer` and `AvroObjectContainer`'s `OutputStream`/`InputStream`
  entry points (`openWriter`, `decodeFromStream`).
- **#320:** `Record` and `ListRecord` are removed; `encodeToGenericData` returns an Apache `GenericData.Record`
  (mutable, unlike `ListRecord`; it equals another `GenericData.Record` with the same schema and values, where
  `ListRecord`'s data-class `equals` only matched another `ListRecord`).
- The deprecated `GenericData` API (`Avro.encodeToGenericData` / `decodeFromGenericData`, all six overloads) now comes
  from the `avro4k-apache-interop` artifact (`com.github.avro-kotlin.avro4k:avro4k-apache-interop`): same package,
  names, signatures and facade class (`AvroGenericDataExtensionsKt`), so source and Java callers only add the
  dependency. Fixed on the way: a plain `java.util.List` (not a `GenericArray`) in a nullable position now decodes
  when its element serializer reads the writer schema (a decimal, a nested plain list), instead of failing with
  `Not an array` (see [`m3-19.md`](m3-19.md)).
- **#429:** on the JVM, a `uuid` (string or `fixed(16)`) decoded as `Any` is now a `kotlin.uuid.Uuid`, no longer a
  `java.util.UUID` — the default of `AvroConfiguration.logicalTypes`, so it applies to `AnySerializer`, to
  `Avro.decodeFromByteArray<Any>`, to `@Contextual Any` fields and to everything decoded generically by the Confluent
  serdes (`GenericAvro4kKafkaDeserializer`/`ReflectAvro4kKafkaDeserializer`: a `uuid` value at the root or in a
  `GenericRecord` field). Back to the old type: `Avro { setLogicalTypeSerializer("uuid", UUIDSerializer) }`.
  Unchanged: a property typed `java.util.UUID` (with `@Contextual` or `UUIDSerializer`) encodes and decodes as before,
  and encoding through `Any` accepts both a `java.util.UUID` and a `kotlin.uuid.Uuid`, so a decoded generic value
  re-encodes to the same bytes. Code generation (kotlin-generator and the gradle plugin) types `uuid` properties as
  `kotlin.uuid.Uuid` with `KotlinUuidSerializer`; back to `java.util.UUID`:
  `logicalTypes { register("uuid").asType("java.util.UUID", "com.github.avrokotlin.avro4k.serializer.UUIDSerializer") }`
  (gradle-plugin README). `kotlin.uuid.Uuid` is stable since Kotlin 2.4, so no opt-in is needed with Kotlin 2.4+; a
  project compiling with an older Kotlin API version must opt in to `kotlin.uuid.ExperimentalUuidApi` to *name* the
  type (in generated code too), but not to receive one as `Any`.

- **`AvroDecoder.decodeValue()`** (deprecated) is removed (user decision, 2026-10-09; unit M3-23 in `m3-decomposition.md`).
  Custom serializers read `currentWriterSchema` and decode with the matching typed `decode*` method, or use the
  `decodeResolving*` helpers, as its deprecation message already said.

## Platform differences (from [`m3-13.md`](m3-13.md), core moving to `commonMain`) — document prominently

These are **silent**: the same code behaves differently on the JVM and elsewhere.

- ~~**`validateSerialization`** checks every call against the writer schema **only on the JVM**~~ — no longer a
  platform difference: since [`m3-18.md`](m3-18.md) avro4k's own validating codec runs on every platform.
- **`Avro.Default`'s serializers module**: the Kotlin standard library serializers and `AnySerializer` everywhere; the
  java.time, `UUID`, `BigDecimal`, `BigInteger` and `URL` serializers on the JVM only.
- **`AvroConfiguration.logicalTypes`' defaults** (what `AnySerializer` decodes a logical type to): `duration` as
  `AvroDuration` and `uuid` as `kotlin.uuid.Uuid` everywhere (since [`m3-21.md`](m3-21.md), #429, `uuid` is no longer
  a platform difference); on the JVM `date`/`time-*`/`timestamp-*` as java.time types, `decimal`/`big-decimal` as
  `BigDecimal`; on JS and native `timestamp-millis`/`-micros` as `kotlin.time.Instant` (the others decode as their
  underlying Avro type).
- **Error messages** name classes by their qualified name on the JVM and native, by their simple name on JS.
- `AnySerializer`'s encoding hook for non-serializable types takes a `Class` on the JVM (unchanged) and a `KClass` on
  the other platforms (internal API).
- **`AnySerializer` encoding of generic classes** (from [`m3-15.md`](m3-15.md)): on JS and native, without reflection,
  the type arguments of a runtime class are unknown, so a generic serializable class that is neither a `Collection` nor
  a `Map` (e.g. `Pair`, `Triple`, a user's `Box<T>`) is **not inferred** when encoded as `Any` — it fails with "Cannot
  find serializer". The JVM infers it with nullable-`Any` type arguments, as before. Workaround: a contextual
  serializer, or overriding the `KClass` hook.
- The deprecated `AvroDecoder.decodeValue()` of a fixed field default returns an Apache `GenericData.Fixed` on the JVM,
  a `ByteArray` elsewhere.
- IDE JSON highlighting of `@AvroProp`/`@AvroDefault` values is gone (the JVM-only `@Language` annotation was dropped).
