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
| `AvroEncoder.currentWriterSchema` | `Schema` | `AvroSchema` |
| `AvroDecoder.currentWriterSchema` | `Schema` | `ResolvedSchema` (never a union; since M3-24, user request) |
| `AvroDecoder.decodeFixed()` | `GenericFixed` | `ByteArray` (for a `GenericFixed`: `GenericData.Fixed(currentWriterSchema.toApacheSchema(), decodeFixed())`) |
| `decodeResolving*` resolver lambdas | `(Schema) -> …` | removed by M3-24 (see below) |
| `AvroSerializer.getSchema(context)` and all built-in serializers | `Schema` | `AvroSchema` (from Apache: `toAvro4k()`) |
| `AvroStringable.createSchema()`, `AvroFixed.createSchema(elementLocation)` | `Schema` | `AvroSchema` |
| `AvroSingleObject(schemaRegistry)` lambda, `encodeToSink` / `encodeToStream` / `encodeToByteArray(writerSchema, …)` | `Schema` | `AvroSchema` |
| `AvroObjectContainer.openWriter(schema, …)` | `Schema` | `AvroSchema` |
| `MissingFieldsEncodingException(fields, writerSchema[, descriptor])` | `List<Schema.Field>, Schema` | `List<AvroSchema.RecordSchema.Field>, AvroSchema` |
| `AnySerializer` hooks (experimental since M3-24); internal-API (`@InternalAvro4kApi`): `trySelectNamedSchema`, `namedSchemaNotFoundInUnionError`, `isNamedSchema` | `Schema`, `Schema.Type` | `AvroSchema`, `AvroSchema.Type` |

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
- The `GenericData` API (`Avro.encodeToGenericData` / `decodeFromGenericData`, all six overloads) now comes
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
  type in its own code, but not to receive one as `Any`. Generated code opts in by itself (next item).

- **Opt-ins and annotations on generated files** ([`m3-26.md`](m3-26.md)). Each generated file has one
  `@file:OptIn(...)` whose markers are now **sorted by simple name** (`ExperimentalAvro4kApi`, then `InternalAvro4kApi`:
  the two lines swap in every generated file, nothing else) and also hold the markers of the types the file uses: a
  file naming `kotlin.uuid.Uuid` opts in to `kotlin.uuid.ExperimentalUuidApi`, so generated code compiles with a Kotlin
  API version below 2.4 (no warning on 2.4+, where the marker still exists). New in the gradle plugin, additions only:
  `logicalTypes { register("x").requiresOptIn("your.Marker").asType(…) }` (only the files using `x` opt in), and
  `sourcesGeneration { fileAnnotations { optIn("your.Marker"); annotation("kotlin.Suppress", "\"Name\"") } }` for every
  generated file. A user who committed generated sources sees the two-line reordering on the next generation.

- **`AvroDecoder.decodeValue()`** (deprecated, `@ExperimentalAvro4kApi`) is removed from the public `AvroDecoder`
  interface (user decision, 2026-10-09; [`m3-23.md`](m3-23.md)). What it did depended on the decoder: the binary
  decoder (`decodeFromByteArray`, `decodeFromSource`, Confluent) **always threw** `UnsupportedOperationException`;
  a missing field's default returned a boxed primitive, a `String` (string or enum), a `ByteArray` (bytes) or a fixed
  (Apache `GenericData.Fixed` on the JVM, `ByteArray` elsewhere), and threw for a structure; the deprecated
  `decodeFromGenericData` returned the raw generic value. Replacement, in a custom serializer:
  - a value whose type the serializer expects: read `currentWriterSchema` if needed, then call the matching typed
    method (`decodeInt()`, `decodeString()`, `decodeBytes()`, `decodeFixed()`, …);
  - a value of any type (what `decodeValue()` was meant for): `decoder.decodeSerializableValue(AnySerializer())`. It
    returns the Kotlin values `AnySerializer` decodes (`AvroConfiguration.logicalTypes` applied; a fixed as `ByteArray`,
    an enum as `String`, a record or a map as `Map<String, Any?>`, an array as `List<Any?>`), never an Apache
    `GenericData` value. `AnySerializer` (and its superclass `AbstractAnySerializer`, which holds `nullable` and the
    decoding hooks) is **`@ExperimentalAvro4kApi`** since M3-24 (it was `@InternalAvro4kApi`: user decision
    2026-10-10), and works on every avro4k decoder: from bytes, on a missing field's default and through
    `decodeFromGenericData` (it used to throw a `ClassCastException` on the last two: [`m3-24.md`](m3-24.md)).
  Nothing else in avro4k (Confluent, kotlin-generator, gradle-plugin, benchmark) called it.

- **`decodeResolving*` are removed** (user decision, 2026-10-10; [`m3-24.md`](m3-24.md)): `AvroDecoder.decodeResolvingAny`,
  `decodeResolvingBoolean`, `…Byte`, `…Short`, `…Int`, `…Long`, `…Float`, `…Double`, `…Char` (all
  `@ExperimentalAvro4kApi`) and their `AnyValueDecoder`, `BooleanValueDecoder`, `ByteValueDecoder`, `ShortValueDecoder`,
  `IntValueDecoder`, `LongValueDecoder`, `FloatValueDecoder`, `DoubleValueDecoder`, `CharValueDecoder` fun interfaces.
  They walked a union's branches to find one the resolver accepted; every avro4k decoder now reports the value's own
  branch as `currentWriterSchema` (never a union), so a custom serializer reads it and switches on its type:
  ```kotlin
  // before
  decoder.decodeResolvingAny({ SerializationException("unsupported") }) { schema ->
      when (schema.type) {
          AvroSchema.Type.LONG -> AnyValueDecoder { Instant.ofEpochMilli(decoder.decodeLong()) }
          AvroSchema.Type.STRING -> AnyValueDecoder { Instant.parse(decoder.decodeString()) }
          else -> null
      }
  }
  // now
  val schema = decoder.currentWriterSchema
  when (schema.type) {
      AvroSchema.Type.LONG -> Instant.ofEpochMilli(decoder.decodeLong())
      AvroSchema.Type.STRING -> Instant.parse(decoder.decodeString())
      else -> throw SerializationException("unsupported")
  }
  ```
  The built-in serializers were rewritten so, with the same results and the same error messages.
- **The generic data decoder resolves unions from the generic value** (M3-24): `decodeFromGenericData` used to give
  serializers the whole union as `currentWriterSchema`. It now gives the branch holding the value, as the binary decoder
  gives the branch of the written index: `null` → null; an Apache record, fixed or enum symbol → the named branch of its
  schema's full name; a map → map; a collection → array; a char sequence → string; a `ByteBuffer`/`ByteArray` → bytes;
  a boxed primitive → its type; nothing matching exactly → the only non-null branch, else a `SerializationException`
  when the schema is read. **Only previously-wrong multi-branch cases change**: every `["null", X]` field decodes as
  before, while a value of a later branch of a union with several non-null branches is no longer read with the first
  matching one (e.g. a `long` timestamp in `["null", "string", timestamp-millis]` failed, a uuid string in
  `["null", fixed-uuid, "string"]` silently became a wrong UUID, a decimal fixed was read with another fixed's scale).
  Also fixed: a nullable `AvroDuration` or `kotlin.time.Duration` (`["null", duration fixed]`) failed through
  `decodeFromGenericData`; `AnySerializer` works there and on a missing field's default (it threw
  `ClassCastException`), and decodes a record as a map and an enum as a string there too.
- **`AvroDecoder.currentWriterSchema` returns a `ResolvedSchema`** (user request, 2026-10-10, M3-24), no longer an
  `AvroSchema`: it is never a union (the branch holding the value). Kotlin callers compile unchanged (a subtype);
  already-compiled callers must be recompiled (the JVM return type changed). The binary decoder now resolves a union
  still unresolved when the schema is read (e.g. on the decoder `decodeInlineElement` returns, before any decode) by
  reading its index, where it used to return the union.
- **`AvroDecoder.avro` and `AvroEncoder.avro`** (user decision, 2026-10-10; [`m3-25.md`](m3-25.md)): both interfaces
  gain a public `val avro: Avro`, the format instance the decoder or encoder runs with, as kotlinx-serialization's
  `JsonDecoder.json` / `JsonEncoder.json`. A custom serializer reads `decoder.avro.configuration` (e.g. its
  `logicalTypes`) or `encoder.avro.serializersModule` without being handed the `Avro` instance. **Breaking only for a
  third-party class implementing `AvroDecoder` or `AvroEncoder`** (a test fake, a wrapping decoder): it must now
  implement `override val avro: Avro` (return the instance it decodes/encodes for, or the one it wraps). Callers and
  serializers compile unchanged. `AnySerializer` now works on such a third-party `AvroDecoder` too, with the logical
  types of its `avro`; a decoder that is not an `AvroDecoder` still fails with a `ClassCastException`.
- **The `GenericData` API is no longer deprecated** (user decision, 2026-10-10): `encodeToGenericData` /
  `decodeFromGenericData` (all six overloads) lose `@Deprecated` now that they live in their own artifact,
  `avro4k-apache-interop`. They stay `@ExperimentalAvro4kApi`. Callers can drop their `@Suppress("DEPRECATION")`.

## Core without Apache Avro (from [`m3-14.md`](m3-14.md))

- **`AvroObjectContainer` moves to `avro4k-apache-interop`.** Same package (`com.github.avrokotlin.avro4k`), same
  public API and JVM classes (`AvroObjectContainer`, `AvroObjectContainerKt`, the builder, writer and metadata
  classes, byte-identical in the `.api` dumps): only the artifact changes. Who: anyone writing or reading object
  container files. What to do: add a dependency on `com.github.avro-kotlin.avro4k:avro4k-apache-interop` (the BOM
  lists it); no source change.
- **Core no longer brings Apache Avro, nor Jackson, transitively.** `avro4k-core`'s published POM and Gradle module
  metadata have no `org.apache.avro:avro` dependency on any platform. Who: code that uses Apache's API
  (`SchemaNormalization`, `GenericData`, `CodecFactory`, `Schema.Parser`, Jackson…) and only got it through
  `avro4k-core`. What to do: depend on `avro4k-apache-interop` (which has `api` on Apache Avro) or on
  `org.apache.avro:avro` directly. `avro4k-confluent-kafka-serializer` now declares Apache Avro itself (`api`, in
  place of the one it excludes from Confluent's serializer), so Confluent users are not affected.
- **`encodeWithApacheEncoder` / `decodeWithApacheDecoder` (internal API) move to `avro4k-apache-interop`**, same package
  (`com.github.avrokotlin.avro4k.internal`), same signatures, but their JVM facade class changes from
  `AvroInternalExtensionsKt` to `ApacheCodecKt` (two jars must not hold one class). Who: only `@InternalAvro4kApi`
  users; Kotlin callers recompile unchanged once the artifact is there, Java or already-compiled callers must be
  recompiled. The four internal forwarders the generic data trees use (`resolveRecordFields`, `enumDefaultIndex`,
  `interceptedDeserializer`, `defaultValueDecoder`) stay in core, facade `AvroInternalExtensionsKt` unchanged.
- **The decimal serializers throw `kotlinx.serialization.SerializationException` instead of Apache's
  `AvroTypeException`** when a `BigDecimal` does not fit the schema's scale (without rounding) or precision, with the
  same message as before (Apache's `DecimalConversion`'s). Who: code catching `org.apache.avro.AvroTypeException` (or
  `AvroRuntimeException`) around encoding decimals. What to do: catch `SerializationException`. The exception hierarchy
  for avro4k's failures is designed in a later unit and may narrow this to a subclass.
- `AvroSingleObject`'s fingerprints are computed by core itself, byte for byte Apache's
  `SchemaNormalization.parsingFingerprint("CRC-64-AVRO", …)`: no change in the bytes. Its KDoc no longer links Apache's
  `SchemaNormalization`; computing the registry's fingerprints on the JVM still can use it (with Apache Avro on the
  classpath). A public fingerprint function for the other platforms would be a new API (open, see the `PROGRESS.md`
  decision log of 2026-09-24).

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
- ~~The deprecated `AvroDecoder.decodeValue()` of a fixed field default returns an Apache `GenericData.Fixed` on the
  JVM, a `ByteArray` elsewhere.~~ — no longer a platform difference: `decodeValue()` is removed ([`m3-23.md`](m3-23.md)).
- IDE JSON highlighting of `@AvroProp`/`@AvroDefault` values is gone (the JVM-only `@Language` annotation was dropped).
