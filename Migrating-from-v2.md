Here is the guide of how to migrate from Avro4k v2 to v3 using examples.

Avro4k v3 is a Kotlin Multiplatform library: the core runs on the JVM, JS and Kotlin/Native, and no longer depends on the Apache Avro library.
Most applications only need to change their dependencies and their `Schema` imports. The rest of this guide is organised by what you do with the library, so
jump to the sections that match your usage.

> [!NOTE]
> If you are missing a migration need, please [file an issue](https://github.com/avro-kotlin/avro4k/issues/new/choose) or [make a PR](https://github.com/avro-kotlin/avro4k/compare).

**Contents**

- [Quick checklist](#quick-checklist)
- [Dependencies and modules](#dependencies-and-modules)
- [The schema type: `AvroSchema`](#the-schema-type-avroschema)
- [Encoding and decoding API](#encoding-and-decoding-api)
- [Removed APIs and their replacements](#removed-apis-and-their-replacements)
- [Writing custom serializers](#writing-custom-serializers)
- [Exceptions](#exceptions)
- [Schema generation: behaviour changes](#schema-generation-behaviour-changes)
- [Decoding and validation: behaviour changes](#decoding-and-validation-behaviour-changes)
- [Platform notes](#platform-notes)
- [Confluent schema registry](#confluent-schema-registry)
- [kotlin-generator and the gradle plugin](#kotlin-generator-and-the-gradle-plugin)

# Quick checklist

1. Maven only: depend on `avro4k-core-jvm` instead of `avro4k-core` (Gradle users keep `avro4k-core`).
2. If you use Apache's classes (`Schema`, `GenericRecord`, `CodecFactory`, `SchemaNormalization`…), `AvroObjectContainer`, or `encodeToGenericData` /
   `decodeFromGenericData`: add the `avro4k-apache-interop` dependency.
3. Replace `org.apache.avro.Schema` by `com.github.avrokotlin.avro4k.AvroSchema` where you pass a schema to avro4k or get one from it; convert with
   `toAvro4k()` / `toApacheSchema()` where you still need Apache's one.
4. Replace the removed stream and okio overloads by the kotlinx-io ones.
5. If you have custom serializers: `getSchema` returns an `AvroSchema`, `decodeFixed()` returns a `ByteArray`, and the `decodeResolving*` functions and
   `decodeValue()` are gone.
6. If you use `@AvroFixed`, regenerate and review your schemas: the full names of the generated fixed types changed.
7. If you decode `uuid` values as `Any`, you now get `kotlin.uuid.Uuid`.

# Dependencies and modules

## `avro4k-core` does not bring Apache Avro anymore

The core module has no dependency on Apache Avro (nor Jackson, nor okio), on any platform. If your code uses Apache's API and only got it through
`avro4k-core`, add the dependency yourself:

```kotlin
dependencies {
    implementation("com.github.avro-kotlin.avro4k:avro4k-core:$avro4kVersion")
    // For Apache's API, the conversions with avro4k's schemas, GenericData and object container files (JVM only):
    implementation("com.github.avro-kotlin.avro4k:avro4k-apache-interop:$avro4kVersion")
    // Or only Apache Avro itself:
    implementation("org.apache.avro:avro:$apacheAvroVersion")
}
```

`avro4k-apache-interop` has Apache Avro as an `api` dependency, so it brings it. `avro4k-confluent-kafka-serializer` declares Apache Avro itself, so
Confluent users are not affected.

## The `avro4k-apache-interop` module (JVM only)

This new module holds everything that is built on Apache Avro. The package names, the class names and the signatures are unchanged for what moved: **only
the dependency changes, the sources stay the same**.

| What | Where it comes from in v3 |
|---|---|
| `AvroObjectContainer` (object container files) and its builder, writer and metadata classes | `avro4k-apache-interop` |
| `Avro.encodeToGenericData` / `Avro.decodeFromGenericData` (all six overloads) | `avro4k-apache-interop` |
| `AvroSchema.toApacheSchema()` and `org.apache.avro.Schema.toAvro4k()` | `avro4k-apache-interop` (new) |
| `AvroObjectContainerBuilder.codec(CodecFactory)` | `avro4k-apache-interop`, still Apache-typed |

The `GenericData` functions are no longer `@Deprecated` (they stay `@ExperimentalAvro4kApi`), so you can drop your `@Suppress("DEPRECATION")`.

The internal API (`@InternalAvro4kApi`) functions `encodeWithApacheEncoder` / `decodeWithApacheDecoder` moved to this module too, with the same package and
signatures, but their JVM facade class is now `ApacheCodecKt` (it was `AvroInternalExtensionsKt`): recompile code that uses them.

## Kotlin Multiplatform and Maven

Gradle resolves `avro4k-core` to the right platform artifact by itself, whatever the target of your project: `avro4k-core-jvm`, `avro4k-core-js`,
`avro4k-core-linuxx64`, `avro4k-core-macosarm64`… The supported targets are the JVM, JS (browser and Node.js) and the whole Kotlin/Native matrix (Apple, Linux,
Windows and Android native targets). wasm is not supported yet.

```kotlin
kotlin {
    jvm()
    js(IR) { nodejs() }
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation("com.github.avro-kotlin.avro4k:avro4k-core:$avro4kVersion")
        }
    }
}
```

**Maven** does not read Gradle's module metadata, so it needs the JVM artifact:

```xml
<!-- Before -->
<dependency>
    <groupId>com.github.avro-kotlin.avro4k</groupId>
    <artifactId>avro4k-core</artifactId>
    <version>${avro4k.version}</version>
</dependency>

<!-- Now -->
<dependency>
    <groupId>com.github.avro-kotlin.avro4k</groupId>
    <artifactId>avro4k-core-jvm</artifactId>
    <version>${avro4k.version}</version>
</dependency>
```

The `avro4k-bom` manages the version of `avro4k-core-jvm` too, as well as `avro4k-apache-interop`, `avro4k-confluent-kafka-serializer` and
`avro4k-kotlin-generator`.

The artifact ids stay `avro4k-<module>`, with the platform suffix of the Kotlin Multiplatform modules (`-jvm`, `-js`, `-linuxx64`…). The javadoc jar of the
JVM artifacts is now generated by Dokka (HTML).

## Okio is not a dependency anymore

Core no longer depends on okio, so it does not come transitively anymore. If you used okio only through avro4k, add it yourself, and see
[the removed okio overloads](#removed-apis-and-their-replacements).

# The schema type: `AvroSchema`

Every schema in the public API of the core is avro4k's own multiplatform `AvroSchema` (package `com.github.avrokotlin.avro4k`) instead of Apache's
`org.apache.avro.Schema`. The names and the parameters of the functions are unchanged: only the type changes.

| API | v2 | v3 |
|---|---|---|
| `Avro.schema(descriptor)`, `Avro.schema(serializer)`, `Avro.schema<T>()` | `Schema` | `AvroSchema` |
| `Avro.encodeToByteArray(writerSchema, …)` / `decodeFromByteArray(writerSchema, …)` (and the reified ones) | `Schema` | `AvroSchema` |
| `encodeToSink` / `decodeFromSource` (kotlinx-io), `decodeFromByteBuffer` | `writerSchema: Schema` | `AvroSchema` |
| `AvroEncoder.currentWriterSchema` | `Schema` | `AvroSchema` |
| `AvroDecoder.currentWriterSchema` | `Schema` | `ResolvedSchema` (a subtype of `AvroSchema`, never a union) |
| `AvroSerializer.getSchema(context)` and every built-in serializer | `Schema` | `AvroSchema` |
| `AvroStringable.createSchema()`, `AvroFixed.createSchema(elementLocation)` | `Schema` | `AvroSchema` |
| `AvroSingleObject(schemaRegistry)`: the lambda | `(Long) -> Schema?` | `(Long) -> AvroSchema?` |
| `AvroSingleObject.encodeToSink` / `encodeToByteArray(writerSchema, …)` | `Schema` | `AvroSchema` |
| `AvroObjectContainer.openWriter(schema, …)` | `Schema` | `AvroSchema` |
| `MissingFieldsEncodingException(fields, writerSchema[, descriptor])` | `List<Schema.Field>, Schema` | `List<AvroSchema.RecordSchema.Field>, AvroSchema` |

Kotlin code that only passes the schema from `Avro.schema()` to the encoding functions compiles unchanged. Already-compiled callers must be recompiled, as the
JVM signatures changed.

## Using the schema

`AvroSchema` is a sealed class with one subclass per Avro type (`AvroSchema.RecordSchema`, `AvroSchema.StringSchema`, `AvroSchema.UnionSchema`…), and the
named ones have a `Name`. Its `toString()` is **not** the JSON anymore: use `toJsonElement()`.

```kotlin
// Before
val schema: Schema = Avro.schema<Project>()
println(schema.toString(true)) // pretty JSON

// Now
val schema: AvroSchema = Avro.schema<Project>()
println(schema.toJsonElement()) // JSON, as a kotlinx-serialization JsonElement
```

To get a pretty JSON, use your own `Json { prettyPrint = true }` on the `JsonElement`, or convert to Apache's schema (`schema.toApacheSchema().toString(true)`).

## Parsing a schema

```kotlin
// Before
val writerSchema: Schema = Schema.Parser().parse(json)

// Now, without Apache
val writerSchema: AvroSchema = AvroSchema.fromJsonString(json)
// or from a kotlinx-serialization JsonElement
val writerSchema: AvroSchema = AvroSchema.fromJsonElement(jsonElement)
```

An invalid schema throws an `AvroSchemaParseException` (still an `IllegalArgumentException`). Two different named types with the same full name are rejected,
as Apache's parser does.

## Converting from and to Apache's `Schema` (`avro4k-apache-interop`)

```kotlin
import com.github.avrokotlin.avro4k.toApacheSchema
import com.github.avrokotlin.avro4k.toAvro4k

val apacheSchema: org.apache.avro.Schema = Avro.schema<Project>().toApacheSchema()
val avro4kSchema: AvroSchema = apacheSchema.toAvro4k()
```

Good to know about the conversions:

- `toApacheSchema()` keeps the `char` logical type and the custom logical types.
- The fields' integer defaults of a converted schema are parser-typed, so `Schema.Field.defaultVal()` is non-null for them.
- An Apache schema equal to one that was already converted maps to the first conversion's result (the docs and aliases of the first one win, as the
  schema caches of v2 did).
- `AvroSchema.logicalTypeName` returns `null`, instead of throwing, when the `logicalType` property is not a JSON primitive.

## Differences when using `AvroSchema` directly

- `RecordSchema.getFieldByName("x")` fails with `No field named 'x' in record 'R'`. The duplicate-field error names the duplicate (the
  `Record fields must be unique` prefix is kept).
- `FixedSchema.size` is an `Int`.
- A union branch is a `ResolvedSchema`: a union cannot directly contain another union, as in the Avro specification.

## Fingerprints

`AvroSingleObject` computes its CRC-64-AVRO fingerprints itself, byte for byte what Apache's `SchemaNormalization.parsingFingerprint("CRC-64-AVRO", …)`
does, so nothing changes in the bytes. On the JVM you can still compute the registry's keys with Apache's `SchemaNormalization` (on `toApacheSchema()`).
Avro4k has no public fingerprint function for the other platforms.

# Encoding and decoding API

## Kotlin streams and kotlinx-io

The deprecated stream and okio overloads are removed, see below. Use `encodeToSink` and `decodeFromSource` with kotlinx-io:

```kotlin
// Before
Avro.encodeToStream(data, outputStream)
val obj = Avro.decodeFromStream<Project>(inputStream)

// Now
val sink = outputStream.asSink().buffered()
Avro.encodeToSink(data, sink)
sink.flush() // or close()

val obj = Avro.decodeFromSource<Project>(inputStream.asSource().buffered())
```

A buffered kotlinx-io `Source` reads ahead. Decode the consecutive values from the **same** `Source` rather than wrapping the stream again for each value.

## `decodeFixed()` returns a `ByteArray`

`AvroDecoder.decodeFixed()` returns the bytes instead of an Apache `GenericFixed`. If you need a `GenericFixed`:

```kotlin
// Before
val fixed: GenericFixed = decoder.decodeFixed()

// Now
val bytes: ByteArray = decoder.decodeFixed()
// With avro4k-apache-interop:
val fixed = GenericData.Fixed(decoder.currentWriterSchema.toApacheSchema(), decoder.decodeFixed())
```

## `AvroDecoder.avro` and `AvroEncoder.avro`

Both interfaces gain a `val avro: Avro`, the format instance they run with (like `JsonDecoder.json`). A custom serializer can read
`decoder.avro.configuration` (for example its `logicalTypes`) without being handed the `Avro` instance.

This breaks **only** classes that implement `AvroDecoder` or `AvroEncoder` themselves (a test fake, a wrapping decoder): they must implement
`override val avro: Avro`. Callers and serializers compile unchanged.

## `AvroDecoder.currentWriterSchema` is never a union

Every avro4k decoder gives the schema of the branch that holds the value, never the whole union. Code that checked for a union and walked the branches by
hand can drop that part. The binary decoder now resolves a union that is still unresolved when the schema is read (for example on the decoder returned by
`decodeInlineElement`, before any decoding) by reading its index; it used to return the union.

## `GenericData`

`Avro.encodeToGenericData` now returns an Apache `GenericData.Record` for a record (see [`ListRecord` and `Record`](#removed-apis-and-their-replacements)).

`decodeFromGenericData` gives the serializers the branch of the union that holds the value, as the binary decoder does, instead of the whole union:
`null` is null, an Apache record, fixed or enum symbol selects the branch of its schema's full name, a map selects a map, a collection an array, a char
sequence a string, a `ByteBuffer` or `ByteArray` bytes, a boxed primitive its type. Every `["null", X]` field decodes as before. Only multi-branch unions
change, and for the better: a value of a later branch used to be read with the first matching branch (for example a `long` timestamp in
`["null", "string", timestamp-millis]` failed, and a uuid string in `["null", fixed-uuid, "string"]` silently became a wrong UUID). A nullable `AvroDuration` or
`kotlin.time.Duration` also decodes through generic data now. A plain `java.util.List` (not a `GenericArray`) in a nullable position now decodes when its
element serializer reads the writer schema (a decimal, a nested list), instead of failing with `Not an array`.

# Removed APIs and their replacements

| Removed | Replacement |
|---|---|
| `Avro.encodeToSink` / `decodeFromSource` on `okio.BufferedSink` / `BufferedSource` (all three shapes of each) | The kotlinx-io overloads. Bridge an okio stream with the `kotlinx-io-okio` module: `source.asKotlinxIoRawSource().buffered()`, `sink.asKotlinxIoRawSink().buffered()` (then flush the sink). The removed decoding overload consumed exactly the decoded bytes of the okio source; a buffered kotlinx-io source reads ahead (see above). |
| `Avro.encodeToStream` / `decodeFromStream` (all three shapes of each) and `AvroSingleObject.encodeToStream` / `decodeFromStream` | The kotlinx-io overloads: `outputStream.asSink().buffered()` and `inputStream.asSource().buffered()` (flush or close the sink). |
| `Record` and `ListRecord` | `encodeToGenericData` returns an Apache `GenericData.Record`. It is mutable, and equals another `GenericData.Record` with the same schema and values (the `equals` of `ListRecord` only matched another `ListRecord`). Use the Apache API to read it. |
| `AvroDecoder.decodeValue()` | In a custom serializer: read `currentWriterSchema` if needed, then call the typed method that matches (`decodeInt()`, `decodeString()`, `decodeBytes()`, `decodeFixed()`…). For a value of any type: `decoder.decodeSerializableValue(AnySerializer())`. |
| `AvroDecoder.decodeResolvingAny` / `…Boolean` / `…Byte` / `…Short` / `…Int` / `…Long` / `…Float` / `…Double` / `…Char`, `findValueDecoder`, and the fun interfaces `AnyValueDecoder`, `BooleanValueDecoder`, `ByteValueDecoder`, `ShortValueDecoder`, `IntValueDecoder`, `LongValueDecoder`, `FloatValueDecoder`, `DoubleValueDecoder`, `CharValueDecoder` | Read `decoder.currentWriterSchema` (never a union) and switch on its type, see below. |
| The `org.apache.avro.limits.*` system properties (for avro4k's own codecs) | See [Malformed and oversized input](#malformed-and-oversized-input). |
| The IDE JSON highlighting of the `@AvroProp` / `@AvroDefault` values (the JVM-only `@Language` annotation) | Nothing: no effect on the runtime nor on the API. |

Not removed: `Avro.decodeFromByteBuffer`, and the `OutputStream` / `InputStream` entry points of `AvroObjectContainer` (`openWriter`, `decodeFromStream`).

## From `decodeResolving*` to `currentWriterSchema`

```kotlin
// Before
decoder.decodeResolvingAny({ SerializationException("unsupported") }) { schema ->
    when (schema.type) {
        Schema.Type.LONG -> AnyValueDecoder { Instant.ofEpochMilli(decoder.decodeLong()) }
        Schema.Type.STRING -> AnyValueDecoder { Instant.parse(decoder.decodeString()) }
        else -> null
    }
}

// Now
val schema = decoder.currentWriterSchema
when (schema.type) {
    AvroSchema.Type.LONG -> Instant.ofEpochMilli(decoder.decodeLong())
    AvroSchema.Type.STRING -> Instant.parse(decoder.decodeString())
    else -> throw SerializationException("unsupported")
}
```

The built-in serializers were rewritten this way, with the same results and the same error messages.

## `AnySerializer` is now a public experimental API

`AnySerializer` and its superclass `AbstractAnySerializer` (which holds `nullable` and the decoding hooks) are `@ExperimentalAvro4kApi` instead of
`@InternalAvro4kApi`, and work on every avro4k decoder, also on a missing field's default and through `decodeFromGenericData` (it used to throw a
`ClassCastException` on these two). It returns Kotlin values, never Apache's `GenericData` ones: a fixed as `ByteArray`, an enum as `String`, a record or a
map as `Map<String, Any?>`, an array as `List<Any?>`, the logical types as configured in `AvroConfiguration.logicalTypes`. A decoder that is not an
`AvroDecoder` still fails with a `ClassCastException`.

The hooks you may override take an `AvroSchema` (they took a `Schema`), and the internal-API helpers `trySelectNamedSchema`, `namedSchemaNotFoundInUnionError`
and `isNamedSchema` use `AvroSchema` and `AvroSchema.Type` too.

# Writing custom serializers

`AvroSerializer.getSchema(context)` returns an `AvroSchema`. Build it with the `AvroSchema` classes, or parse it from a JSON string; from an Apache schema,
use `toAvro4k()`.

```kotlin
// Before
override fun getSchema(context: SchemaSupplierContext): Schema {
    return LogicalTypes.uuid().addToSchema(Schema.create(Schema.Type.STRING))
}

// Now
override fun getSchema(context: SchemaSupplierContext): AvroSchema {
    return AvroSchema.StringSchema(mapOf("logicalType" to JsonPrimitive("uuid")))
    // or AvroSchema.fromJsonString("""{"type":"string","logicalType":"uuid"}""")
}
```

The built-in serializers ignore an unknown or invalid logical type on any schema, on every platform.

# Exceptions

## A common base: `AvroSerializationException`

Every avro4k failure is an `AvroSerializationException`: an `open` class that extends kotlinx-serialization's `SerializationException`, so also an
`IllegalArgumentException`. Code that catches `SerializationException` or `IllegalArgumentException` keeps catching everything it did, and more. To tell the
failures apart, catch one of the families (all `@ExperimentalAvro4kApi`):

| Exception | Raised when |
|---|---|
| `AvroSchemaGenerationException` (now public, with the `path` property) | The schema cannot be generated from your classes and annotations |
| `AvroSchemaParseException` | A schema JSON is invalid (`AvroSchema.fromJsonString` / `fromJsonElement`) |
| `AvroSchemaMismatchException` | A value or a type does not fit the schema (`MissingFieldsEncodingException`, a `BigDecimal` that does not fit a decimal's scale or precision…) |
| `AvroMalformedDataException` | The bytes to decode are invalid |
| `AvroValidationException` (with the `path` property) | `validateSerialization` found a call that does not match the writer schema |

`MissingFieldsEncodingException` now extends `AvroSchemaMismatchException`, and `AvroDurationParseException` extends `AvroSerializationException` (their
names and package are unchanged).

## Exception classes that changed

If you catch one of these exact classes, catch `SerializationException` (or an `AvroSerializationException` family) instead. The original exception is the
`cause` when there was one.

| Situation | v2 | v3 |
|---|---|---|
| A `BigDecimal` that does not fit the decimal's scale (without rounding) or precision | Apache's `AvroTypeException` (same message) | `AvroSchemaMismatchException` |
| An invalid `@AvroDefault` for its field type | Apache's `AvroTypeException` (same message) | `SerializationException` |
| A name, a property or a reserved property that is invalid in a schema | Apache's exceptions | `SerializationException` |
| `validateSerialization` failures | Apache's `AvroTypeException` (and an `ArrayIndexOutOfBoundsException` for an out-of-range union index) | `AvroValidationException` / `AvroMalformedDataException` |
| `AvroSingleObject`'s bad magic or version byte, two Kotlin properties matching one writer field through aliases | `IllegalStateException` | `AvroSerializationException` subclasses |
| Several `@AvroEnumDefault` (at schema generation), a `null` value in apache-interop's polymorphic encoding, an Apache schema default of an unsupported type | `UnsupportedOperationException` | `AvroSerializationException` subclasses |
| A union or enum index out of range, a string that is not a number or a UUID, a date out of range, a `NaN` to `BigInteger`, an empty `big-decimal` unscaled value | `IndexOutOfBoundsException`, `NumberFormatException`, `DateTimeException`, `ArithmeticException`, `NoSuchElementException`, `NullPointerException`… | `AvroMalformedDataException` |
| A decoded `URL` that is malformed | the checked `MalformedURLException` | an unchecked `AvroSerializationException` |
| A malformed length or an overflow while decoding with an Apache decoder (Confluent, object container files) | Apache's `AvroRuntimeException` (`Malformed data. Length is negative`, `SystemLimitException`), `InvalidNumberEncodingException` | `AvroMalformedDataException` |
| `AvroSchema.fromJsonString` / `fromJsonElement` | n/a | `AvroSchemaParseException` (still an `IllegalArgumentException`) |

## Messages

Schema generation messages now end with ` (at '<path>')`, the element that failed from the root type, for example
`com.example.Order.lines[].product<com.example.Digital>.price`. Code that compares these messages exactly must append it, or compare with `startsWith`.

## Truncated input

On every input, including `decodeFromSource`, a truncated input throws `AvroMalformedDataException` (with the `EOFException` as its cause), instead of the
`EOFException` of kotlinx-io or Java. If you read datums back to back from a `Source` until an `EOFException`, check `source.exhausted()` before each decode
instead.

# Schema generation: behaviour changes

These change the generated schemas, so their JSON and their fingerprint, with the possible consequences on a schema registry or on `AvroSingleObject`.

## Fixed type names (`@AvroFixed`)

A fixed type created by `@AvroFixed` (on a `ByteArray`, a `String`, a `BigDecimal` with `@AvroDecimal`, a `java.util.UUID` or a `kotlin.uuid.Uuid`) used to be
named after the annotated property, in the namespace of the class that declares it. Its full name now depends on where `@AvroFixed` is:

| `@AvroFixed` is on | v2 full name | v3 full name |
|---|---|---|
| The property of a **value class**: `@JvmInline value class Hash(@AvroFixed(4) val bytes: ByteArray)` in package `com.example` | `com.example.bytes` | `com.example.Hash`: the value class's **serial name** (so `@SerialName` sets it; a serial name without a dot means no namespace, as for records) |
| A **data class property**: `@AvroFixed(4) val id: ByteArray` in `data class A` of package `ns` | `ns.id` | `ns.A.id`: the property name, in the **record's full name** as namespace |

- `@AvroAlias` and `@AvroDoc` on the value class are now the fixed's aliases and doc (they were ignored on value classes). An alias without a dot is in the
  fixed's namespace.
- When both are present (a data class property annotated with `@AvroFixed` whose type is such a value class), the property's annotation wins, as before, with
  its naming (`<record full name>.<property>`).
- The `fixed(12)` schemas of `java.time.Duration`, `java.time.Period`, `kotlin.time.Duration` and `AvroDuration` are not created from `@AvroFixed`, and keep
  their name. The same goes for a fixed schema returned by a custom serializer that does not call `AvroFixed.createSchema`.
- Two different fixed types with one full name (for example two value classes with the same serial name and different sizes) fail the schema generation with
  `Can't redefine: <full name>. …`. Two records' fields can no longer clash: `ns.A.id` and `ns.B.id` are two names.

**Consequences.**

- The data stays readable in both ways: avro4k reads and writes a fixed by its size, not by its name. Data written by v2 is read by v3, and back.
- A data class property's fixed only changes its namespace, which schema resolution accepts (Apache Java and the schema registries compare the unqualified name):
  a registry's compatibility check passes. But the full name and the schema fingerprint change: a registry registers a **new schema version**, and
  `AvroSingleObject` writes a new fingerprint, that a reader that only knows the old schema cannot resolve. Code generated from the schema by other tools
  (Avro's `SpecificCompiler`, `kotlin-generator`…) moves the fixed class to another package. An Apache `GenericDatumWriter` given a `GenericData.Fixed` built
  with the old schema no longer finds the union branch (`UnresolvedUnionException`).
- A value class's fixed gets a **new simple name**, which schema resolution rejects unless the old full name is an alias of the new fixed (`NAME_MISMATCH`, or
  `MISSING_UNION_BRANCH` in a union). A registry with BACKWARD, FORWARD or FULL compatibility refuses the new schema.

**Keeping the old names.**

```kotlin
package ns

// Before (v2): fixed "ns.id"
@Serializable
data class A(@AvroFixed(4) val id: ByteArray)

// Now, to keep "ns.id": a value class whose serial name is the old full name
@JvmInline
@Serializable
@SerialName("ns.id")
value class Id(@AvroFixed(4) val bytes: ByteArray)

@Serializable
data class A(val id: Id)
```

```kotlin
package ns

// Before (v2): fixed "ns.bytes". Now: "ns.Hash". To stay compatible with the v2 schemas, alias the old name:
@JvmInline
@Serializable
@AvroAlias("ns.bytes")
value class Hash(@AvroFixed(4) val bytes: ByteArray)
// Or @SerialName("ns.bytes") to keep the old name exactly
```

See also the [README](README.md#keep-a-previous-fixed-name).

## Default values

The default values follow the Avro specification (checked against the other implementations, with Apache Java as the tie-breaker):

- A `bytes` or `fixed` default with non-ASCII characters is read as the specification says, with the code points 0 to 255: `"é"` is `E9` (it was `C3 A9`).
- A `bytes` or `fixed` default above `ÿ` is rejected when the schema is generated.
- A `fixed` default of the wrong length is padded or truncated.
- A union field whose default matches a later branch than the first now has that branch first in the generated schema: the reader picks the first branch
  that matches the default, and the generator puts it first so that the schema reads the same in the other implementations of Avro. The schema, and so its
  fingerprint, changes. Before, such a default could not be read.
- `@AvroDefault("null")` on a non-nullable `String` decodes the string `"null"`, as the schema always said.
- A field that a record default leaves out takes its own default, at any depth (records, list items, map values, nullable records): its `@AvroDefault`, else its
  Kotlin default, else the implicit `null` or empty collection, else a failure naming the field. The old behaviour decoded such a key as `null`.
- An `@AvroDefault` JSON with a non-number unquoted token still fails with a `NumberFormatException`, with avro4k's message instead of `BigDecimal`'s.

## Named types

- Two different named types (records, enums, fixed) with the same full name are rejected when the schema is generated or parsed, as Apache's parser does.

# Decoding and validation: behaviour changes

## Malformed and oversized input

- The `org.apache.avro.limits.*` system properties no longer apply to avro4k's own codecs.
- A negative length, or a length above `Int.MAX_VALUE - 8`, fails with an `AvroMalformedDataException` (a `SerializationException`). Every length is checked,
  also the ones of strings and skipped values.
- A collection count above `Int.MAX_VALUE - 8` (summed over its blocks) is malformed data. It used to be silently truncated by `toInt()`. Valid data never has
  one.
- Known limitation: decoding untrusted input whose collections hold items of zero bytes (`null`, a record without fields, `fixed(0)`) can allocate in
  proportion to the claimed count, as in Apache Avro without its `maxAllocation` limit.

## `validateSerialization`

`AvroConfiguration.validateSerialization` (still experimental, off by default) now works on **every platform**, and also for the Apache codecs handed to
`encodeWithApacheEncoder` / `decodeWithApacheDecoder` (Confluent, object container files). Its failures are `AvroValidationException`s (or
`AvroMalformedDataException`s when the invalid index or count was read from the input) naming the call, what was expected and where:

```
Invalid call writeLong() at 'inner.l' for the writer schema: expected a value of type int
```

It is **stricter than Apache's validating codecs**, and so than in v2:

- the encoded or decoded value must be complete, and a call after its end is rejected;
- the collections count their items: one value per `startItem()` when encoding, and every block read to its end when decoding;
- the primitive types must match exactly (no promotion).

## `uuid` decodes to `kotlin.uuid.Uuid`

On the JVM too, a `uuid` (string or `fixed(16)`) decoded as `Any` is now a `kotlin.uuid.Uuid`, no longer a `java.util.UUID`. It is the default of
`AvroConfiguration.logicalTypes`, so it applies to `AnySerializer`, to `Avro.decodeFromByteArray<Any>`, to `@Contextual Any` fields and to everything decoded
generically by the Confluent serializers.

```kotlin
// Back to the old type
val avro = Avro { setLogicalTypeSerializer("uuid", UUIDSerializer) }
```

Unchanged: a property typed `java.util.UUID` (with `@Contextual` or `UUIDSerializer`) encodes and decodes as before, and encoding through `Any` accepts both
a `java.util.UUID` and a `kotlin.uuid.Uuid`, so a decoded generic value re-encodes to the same bytes.

`kotlin.uuid.Uuid` is stable since Kotlin 2.4. With an older Kotlin API version, opt in to `kotlin.uuid.ExperimentalUuidApi` to **name** the type in your own
code (`is Uuid`…); receiving one as `Any` needs no opt-in.

## Other decoding changes

- The decimal serializers throw `AvroSchemaMismatchException` (a `SerializationException`) instead of Apache's `AvroTypeException`, with the same message.
- Built-in serializers ignore unknown or invalid logical types on every schema.

# Platform notes

The same code does not always behave the same everywhere. These are **silent** differences.

| | JVM | JS and Kotlin/Native |
|---|---|---|
| `java.time`, `java.util.UUID`, `BigDecimal`, `BigInteger` and `URL` serializers | In `Avro.Default`'s serializers module | Not available (JVM-only types) |
| Kotlin standard library serializers (`Uuid`, `Instant`, `Duration`…) and `AnySerializer` | Yes | Yes |
| `AnySerializer`'s decoding of `date`, `time-*`, `timestamp-*` | java.time types | `timestamp-millis` and `-micros` as `kotlin.time.Instant`; the other ones decode as their underlying Avro type |
| `AnySerializer`'s decoding of `decimal` and `big-decimal` | `BigDecimal` | The underlying Avro type |
| `duration` and `uuid` in `AnySerializer` | `AvroDuration`, `kotlin.uuid.Uuid` | Same |
| Names of classes in error messages | Qualified name | Qualified name on native, simple name on JS |
| `AnySerializer.inferSerializationStrategyFromNonSerializableType` | Takes a `Class` | Takes a `KClass` (internal API) |
| Encoding a generic class as `Any` (`Pair`, `Triple`, your `Box<T>`…) | The type arguments are inferred as nullable `Any` | **Not inferred** (no reflection): fails with `Cannot find serializer`, unless it is a `Collection` or a `Map`. Register a contextual serializer, or override the `KClass` hook |

`java.time`, `BigDecimal` and `java.util.UUID` serializers are JVM-only: a Kotlin Multiplatform `commonMain` cannot use them. Use `kotlin.time.Instant` (which
needs Kotlin 2.3 or later) and `kotlin.uuid.Uuid` in common code.

`AvroSingleObject` works on every platform. The object container (`AvroObjectContainer`), `GenericData` and Apache's `Schema` stay JVM-only, in
`avro4k-apache-interop`.

# Confluent schema registry

`avro4k-confluent-kafka-serializer` has no API change. What to know:

- It declares Apache Avro itself as an `api` dependency, in place of the one it excludes from Confluent's serializer: you are not affected by core not
  bringing Apache Avro anymore.
- It depends on `avro4k-core` and `avro4k-apache-interop` (JVM), at runtime. Your own code (the `Avro` instance, your serializers) compiles against
  `avro4k-core`, so declare it as in the other modules (`avro4k-core-jvm` with Maven).
- `GenericAvro4kKafkaDeserializer` and `ReflectAvro4kKafkaDeserializer` decode a `uuid` value (at the root or in a `GenericRecord` field) to `kotlin.uuid.Uuid`,
  see [the uuid change](#uuid-decodes-to-kotlinuuiduuid).
- The decoding errors are the `AvroMalformedDataException` described [above](#exceptions), including the ones raised by Apache's decoders.
- `@AvroFixed` schemas change names, see [above](#fixed-type-names-avrofixed): a registry registers a new version of the schemas that have one.

# kotlin-generator and the gradle plugin

- **Fixed types are value classes.** A named fixed is generated as a value class (`@SerialName("<fixed full name>")`, `@AvroAlias`, `@AvroDoc`, and `@AvroFixed`
  on its `value` property) that the fields, array items, map values and union branches reference, instead of a `ByteArray` field annotated with `@AvroFixed`.
  A root fixed schema is generated as that value class, named after the fixed (it was a value class named after the file). Code that used the generated
  fixed fields as `ByteArray` must now go through `.value`. The `duration` fixed is unchanged.
- **`uuid` properties are typed `kotlin.uuid.Uuid`** with `KotlinUuidSerializer`. To keep `java.util.UUID`:

  ```kotlin
  avro4k {
      sourcesGeneration {
          logicalTypes {
              register("uuid").asType("java.util.UUID", "com.github.avrokotlin.avro4k.serializer.UUIDSerializer")
          }
      }
  }
  ```

- **Opt-ins in the generated files.** Each generated file has one `@file:OptIn(...)` whose markers are now sorted by simple name (`ExperimentalAvro4kApi`, then
  `InternalAvro4kApi`: the two lines swap in every generated file) and also hold the markers of the types the file uses: a file naming `kotlin.uuid.Uuid` opts in
  to `kotlin.uuid.ExperimentalUuidApi`, so the generated code compiles with a Kotlin API version below 2.4. If you commit the generated sources, you will see this
  reordering on the next generation.
- **New options** (additions only): `sourcesGeneration { logicalTypes { register("x").requiresOptIn("your.Marker").asType(…) } }` (only the files using `x` opt in), and
  `avro4k { sourcesGeneration { fileAnnotations { optIn("your.Marker"); annotation("kotlin.Suppress", "\"Name\"") } }` for every generated file.
- The plugin keeps adding the dependency on `avro4k-core` by itself. `kotlin-generator` itself still depends on Apache Avro.
