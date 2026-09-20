package com.github.avrokotlin.benchmark.internal.apache

import com.github.avrokotlin.benchmark.internal.specific.CanonicalSchemas
import org.apache.avro.Schema
import org.apache.avro.data.TimeConversions
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DatumReader
import org.apache.avro.io.DatumWriter
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.FastReaderBuilder
import org.apache.avro.reflect.AvroName
import org.apache.avro.reflect.ReflectData
import org.apache.avro.specific.SpecificData
import org.apache.avro.specific.SpecificDatumReader
import org.apache.avro.specific.SpecificDatumWriter
import org.apache.avro.specific.SpecificRecord
import java.lang.reflect.Type

internal object ApacheAvro {
    private const val MODEL_NAMESPACE = "com.github.avrokotlin.benchmark.internal.apache"
    private const val CANONICAL_NAMESPACE = "com.github.avrokotlin.benchmark.internal"

    /**
     * Declares, for [com.github.avrokotlin.benchmark.internal.SchemaEquivalence], the one package in
     * which Apache keeps its copy of the models. Any *other* namespace difference is a failure.
     */
    val NAMESPACE_ALIASES = mapOf(MODEL_NAMESPACE to CANONICAL_NAMESPACE)

    /**
     * The fully configured [ReflectData] the benchmarks and the equivalence gate share.
     *
     * Deliberately **not** [ReflectData.get]: the benchmarks used to mutate that JVM-wide singleton
     * from `@Setup` — which, since `@Param` was introduced, runs several times per fork,
     * re-registering the same conversions onto shared global state. Building it once here is
     * idempotent however many times `@Setup` runs, and nothing outside the benchmark can see it.
     *
     * It is one shared instance rather than one per caller for a measurable reason: `ReflectData`'s
     * field-accessor cache (`ReflectData.ACCESSOR_CACHE`) is **static**, and it maps a schema to the
     * accessors in a `WeakHashMap`. Two `ReflectData` instances produce two equal-but-distinct schema
     * objects for the same class, which then share a hash bucket in that map, and every record write
     * pays a full deep `Schema.equals` to walk past the first one. One instance, one schema, one
     * entry.
     *
     * Only the two conversions the canonical schemas actually use are registered. The `uuid` and
     * `decimal` conversions the benchmarks used to register are gone: no benchmark schema carries
     * either logical type, and `DecimalConversion` is actively harmful here, because
     * `ReflectData.createSchema` consults the registered conversions *before* it considers a
     * stringable class, and `DecimalConversion.getRecommendedSchema()` throws
     * `"No recommended schema for decimal (scale is required)"` — which is what stops it inferring a
     * schema for the `BigDecimal` that avro4k writes as a plain string.
     */
    fun reflectData(): ReflectData = REFLECT_DATA

    private val REFLECT_DATA: ReflectData = DeclarationOrderReflectData().apply {
        addLogicalTypeConversion(TimeConversions.DateConversion())
        addLogicalTypeConversion(TimeConversions.TimestampMillisConversion())
    }

    // -----------------------------------------------------------------------------------------
    // The fast reader (A6)
    //
    // Apache 1.12 ships a second, faster read path: `FastReaderBuilder` pre-compiles a schema into
    // a tree of closures instead of walking the schema per datum. `GenericDatumReader.read` picks it
    // up when `data.isFastReaderEnabled()`.
    //
    // It does **not** apply to `ReflectData`, and cannot be made to:
    //
    //     public static boolean isSupportedData(GenericData data) {
    //       return data.getClass() == GenericData.class || data.getClass() == SpecificData.class;
    //     }
    //
    // That is an exact-class check, and `isFastReaderEnabled()` is
    // `fastReaderEnabled && FastReaderBuilder.isSupportedData(this)` - so calling
    // `reflectData.setFastReaderEnabled(true)` sets a field that is then ignored, and
    // `isFastReaderEnabled()` still answers `false`. Verified against 1.12.1, see
    // docs/plans/notes/a6.md. The exact-class check also rules out `DeclarationOrderReflectData`
    // and any other subclass, so the fast reader is measured on the one data model that does
    // support it: plain `GenericData`.
    //
    // Note also that the flag defaults to **true**
    // (`System.getProperty("org.apache.avro.fastread", "true")`): for `GenericData` and
    // `SpecificData` the fast reader is what a real user gets unless they turn it off. The slow
    // variant here is the opt-out, not the default.
    // -----------------------------------------------------------------------------------------

    /**
     * The two `GenericData` instances the fast-reader comparison reads through.
     *
     * Two instances rather than one mutated from `@Setup`, for two reasons. First, the A5 rule about
     * never mutating JVM-wide state from a benchmark: neither of these is `GenericData.get()`.
     * Second, `GenericDatumReader` caches the fast reader it built on first use
     * (`this.fastDatumReader`), so flipping the flag on a shared instance between `@Param` values
     * would leave already-built readers on the path they were born with. One instance per setting
     * means the setting can never drift from what the reader actually does.
     *
     * They must be *exactly* `GenericData` - see [FastReaderBuilder.isSupportedData] above - so
     * neither may become a subclass. [genericDatumReader] asserts that on every use.
     *
     * The two `ReflectData` instances of A5 cost 40% of Apache's write throughput through the static
     * `ACCESSOR_CACHE`; `GenericData` has no such cache (it has no field accessors to cache), and
     * nothing here writes through them, so the same trap does not apply.
     *
     * No conversions are registered, which is what a stock `new GenericData()` gives a real user: the
     * `date` and `timestamp-millis` fields are read as the `int` and `long` they are on the wire.
     */
    private val GENERIC_DATA_FAST: GenericData = GenericData().setFastReaderEnabled(true)
    private val GENERIC_DATA_SLOW: GenericData = GenericData().setFastReaderEnabled(false)

    fun genericData(fastReader: Boolean): GenericData = if (fastReader) GENERIC_DATA_FAST else GENERIC_DATA_SLOW

    /**
     * A generic reader for [schema], built through the same `GenericData.createDatumReader` a real
     * Apache user calls, with the fast-reader flag *already* in effect on the data model it is built
     * from.
     *
     * Asserts the flag took effect rather than trusting that setting it did something: a benchmark
     * row labelled `fastReader=true` that silently ran the slow path would be a lie, and that is
     * exactly what happens if this is ever pointed at a `GenericData` subclass.
     * [assertFastReaderInEffect] then proves it on the built reader itself.
     */
    fun genericDatumReader(schema: Schema, fastReader: Boolean): DatumReader<GenericRecord> {
        val data = genericData(fastReader)
        check(FastReaderBuilder.isSupportedData(data)) {
            "FastReaderBuilder.isSupportedData(${data.javaClass.name}) is false, so the fast reader " +
                "can never engage. It is an exact-class check: this must be a plain GenericData."
        }
        check(data.isFastReaderEnabled() == fastReader) {
            "Asked for fastReader=$fastReader but ${data.javaClass.name}.isFastReaderEnabled() " +
                "answers ${data.isFastReaderEnabled()}."
        }
        @Suppress("UNCHECKED_CAST")
        return data.createDatumReader(schema) as DatumReader<GenericRecord>
    }

    /**
     * Proves, on the reader that will actually be measured, that it dispatches to the path its
     * `fastReader` label claims.
     *
     * `GenericDatumReader` builds its fast reader lazily, inside `read`, and keeps it in a private
     * `fastDatumReader` field. So the only honest check is: perform one read, then look at the
     * field. Non-null (and a `FastReaderBuilder` inner class) means every subsequent measured read
     * goes straight to `fastDatumReader.read`; null means every one of them goes through
     * `ResolvingDecoder`. Called from `@Setup`, never from a measured method.
     */
    fun assertFastReaderInEffect(reader: DatumReader<*>, bytes: ByteArray, fastReader: Boolean) {
        reader.read(null, DecoderFactory.get().binaryDecoder(bytes, null))
        val field = GenericDatumReader::class.java.getDeclaredField("fastDatumReader").apply { isAccessible = true }
        val installed = field.get(reader)
        if (fastReader) {
            check(installed != null && installed.javaClass.name.startsWith(FAST_READER_CLASS_PREFIX)) {
                "fastReader=true, but after a read GenericDatumReader.fastDatumReader is " +
                    "${installed?.javaClass?.name} - the reader fell back to the slow path, so this row " +
                    "would report the slow reader under the fast reader's name."
            }
        } else {
            check(installed == null) {
                "fastReader=false, but GenericDatumReader.fastDatumReader is " +
                    "${installed.javaClass.name} - this row is not the slow path it claims to be."
            }
        }
    }

    private const val FAST_READER_CLASS_PREFIX = "org.apache.avro.io.FastReaderBuilder"

    // -----------------------------------------------------------------------------------------
    // `SpecificData` + generated `SpecificRecord` classes (A7)
    //
    // The fastest data model Apache offers: the record classes are generated ahead of time from the
    // schema, so there is no reflection on the hot path and no per-datum schema inference. It is
    // also the second of the two data models the fast reader accepts, and - since the flag defaults
    // to `true` - it gets it without configuring anything.
    //
    // The generated classes live in `internal/specific`, are produced by
    // `:benchmark:generateSpecificRecords` from `Avro.schema<T>()`, and are never hand-edited. See
    // docs/plans/notes/a7.md.
    // -----------------------------------------------------------------------------------------

    /**
     * Declares the one package the generated specific classes live in. The generated names are
     * avro4k's own (`Clients`, `Client`, `GoodPartner`, ...), so they need a package of their own or
     * they would collide with the model under test; any *other* namespace difference is a failure.
     */
    val SPECIFIC_NAMESPACE_ALIASES = mapOf(CanonicalSchemas.SPECIFIC_NAMESPACE to CanonicalSchemas.CANONICAL_NAMESPACE)

    /**
     * A writer for a generated class, built exactly the way its users build one:
     * `new SpecificDatumWriter<>(Clients.class)`.
     *
     * Nothing is configured, cached or shared beyond what that constructor does by itself. It picks
     * up `SpecificData.getForClass(c)`, which is the generated class' own `MODEL$` - a plain
     * `new SpecificData()` carrying the `date` and `timestamp-millis` conversions the compiler saw
     * in the schema. Handing it anything else would be a benchmark-only configuration, and would
     * also lose the fast reader on the read side.
     */
    fun <T : SpecificRecord> specificDatumWriter(recordClass: Class<T>): DatumWriter<T> {
        assertSpecificFastReaderAvailable(recordClass)
        return SpecificDatumWriter(recordClass)
    }

    /** A reader for a generated class, built the way its users build one. See [specificDatumWriter]. */
    fun <T : SpecificRecord> specificDatumReader(recordClass: Class<T>): DatumReader<T> {
        assertSpecificFastReaderAvailable(recordClass)
        return SpecificDatumReader(recordClass)
    }

    /**
     * The data model a generated class carries, and the one both of the above end up using.
     *
     * Asserts, rather than assumes, that it is a *plain* `SpecificData` with the fast reader on:
     * [FastReaderBuilder.isSupportedData] is an exact-class check, so the usual way to configure a
     * `SpecificData` - subclassing it - silently costs the fast reader. If a future Avro generates
     * something else into `MODEL$`, this fails in `@Setup` instead of quietly measuring the slow
     * path under the fast one's name. [assertFastReaderInEffect] then proves it on the built reader.
     */
    private fun assertSpecificFastReaderAvailable(recordClass: Class<*>) {
        val data: SpecificData = SpecificData.getForClass(recordClass)
        check(FastReaderBuilder.isSupportedData(data)) {
            "The data model generated into ${recordClass.name}.MODEL\$ is a ${data.javaClass.name}, " +
                "which FastReaderBuilder.isSupportedData rejects - it is an exact-class check. This " +
                "row would measure the slow read path."
        }
        check(data.isFastReaderEnabled()) {
            "${recordClass.name}'s SpecificData reports isFastReaderEnabled()=false. The fast reader " +
                "is on by default in 1.12.1 (org.apache.avro.fastread); something turned it off, and " +
                "this row would not be the configuration a real SpecificData user gets."
        }
    }

    /**
     * The claim that the fast reader does not apply to `ReflectData`, asserted rather than written
     * down, so an Avro upgrade that lifts the restriction is a loud failure instead of a benchmark
     * that quietly keeps measuring only the slow reflect path. Same reasoning as the gate's stale
     * schema exemptions: a documented limitation must not outlive the limitation.
     */
    init {
        check(!FastReaderBuilder.isSupportedData(REFLECT_DATA)) {
            "This version of Apache Avro supports the fast reader for ${REFLECT_DATA.javaClass.name}. " +
                "It did not in 1.12.1, which is why ApacheAvroGenericFastReaderBenchmark measures plain " +
                "GenericData instead. Add a `fastReader` @Param to the ApacheAvroReflect* benchmarks and " +
                "delete this check - see docs/plans/notes/a6.md."
        }
    }
}

/**
 * [ReflectData] with its alphabetical field sort undone.
 *
 * `ReflectData.getFields` does `Arrays.sort(declaredFields, comparing(Field::getName))`, so every
 * schema it infers has its record fields in alphabetical order. Avro's binary encoding is
 * **positional**: the field order *is* part of the wire format, so an alphabetically-ordered schema
 * cannot encode the same bytes as avro4k, which uses the order the properties are declared in.
 *
 * This subclass re-emits each record's fields in the declaration order of the model class, which is
 * exactly the rule avro4k follows. It is not seeded from avro4k's schema: the order comes from the
 * Apache model's own source, so declaring those properties in the wrong order is still caught by
 * [com.github.avrokotlin.benchmark.internal.EquivalenceGate] rather than silently papered over.
 *
 * Only the field *order* is touched; every field's own schema, and every property Avro put on it,
 * is left exactly as `ReflectData` produced it.
 *
 * Caveat: a self-referencing record would keep pointing at the pre-reorder instance for its own
 * recursive branch. None of the benchmark models are recursive, and the equivalence gate would
 * catch it if one became so.
 */
private class DeclarationOrderReflectData : ReflectData() {
    // NOT overridden here, on purpose: `ReflectDatumWriter.write` asks `ReflectData.getCustomEncoding`
    // for a custom encoding on **every datum**, and that is a `ConcurrentHashMap<Schema, …>` lookup
    // whose key comparison is a deep `Schema.equals` (`JsonProperties.propsEqual` compares the whole
    // property map). Replacing it with a cache keyed by schema *identity* is worth **+69% on
    // `complex` write** (358k -> 606k ops/s @ 1 client) — but no Apache user gets that, so measuring
    // it would report Apache as faster than it is. The stock lookup stays. Recorded here because it
    // is the single largest item in Apache's write profile and a future session should not have to
    // rediscover it: see docs/plans/notes/a5.md.

    /**
     * `GenericData.newMap` returns a `java.util.HashMap`, so a decoded Avro map comes back in hash
     * order rather than in the order it was written. avro4k decodes into a `LinkedHashMap` (that is
     * what kotlinx-serialization's map serializer builds), so without this the two libraries cannot
     * re-encode a decoded record to the same bytes, and the round-trip half of the equivalence gate
     * has nothing to compare.
     *
     * This costs Apache a little on the read path rather than saving it any: a `LinkedHashMap` keeps
     * more per entry than a `HashMap`. It is what makes the two libraries do the same work.
     */
    override fun newMap(old: Any?, size: Int): Any =
        (old as? MutableMap<*, *>)?.also { it.clear() } ?: LinkedHashMap<Any?, Any?>(size)

    override fun createSchema(type: Type, names: MutableMap<String, Schema>): Schema {
        val schema = super.createSchema(type, names)
        if (type !is Class<*> || schema.type != Schema.Type.RECORD) return schema

        val declarationOrder = declarationOrderOf(type)
        val reordered = schema.fields.sortedBy { declarationOrder[it.name()] ?: Int.MAX_VALUE }
        if (reordered.map { it.name() } == schema.fields.map { it.name() }) return schema

        val copy = Schema.createRecord(schema.name, schema.doc, schema.namespace, schema.isError)
        schema.objectProps.forEach { (key, value) -> copy.addProp(key, value) }
        copy.fields = reordered.map { Schema.Field(it, it.schema()) }
        names[copy.fullName] = copy
        return copy
    }

    /** Avro field name -> index in the class' declaration order, superclass fields first. */
    private fun declarationOrderOf(type: Class<*>): Map<String, Int> {
        val names = mutableListOf<String>()
        generateSequence(type) { it.superclass }
            .takeWhile { it.`package`?.name?.startsWith("java.") != true }
            .toList()
            .asReversed()
            .forEach { clazz ->
                clazz.declaredFields.forEach { field ->
                    names += field.getAnnotation(AvroName::class.java)?.value ?: field.name
                }
            }
        return names.withIndex().associate { (index, name) -> name to index }
    }
}
