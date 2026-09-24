package com.github.avrokotlin.benchmark.internal

import com.fasterxml.jackson.dataformat.avro.AvroSchema
import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.benchmark.internal.apache.ApacheAvro
import com.github.avrokotlin.benchmark.internal.apache.toApache
import com.github.avrokotlin.benchmark.internal.jackson.JacksonAvro
import com.github.avrokotlin.benchmark.internal.jackson.toJackson
import com.github.avrokotlin.benchmark.internal.specific.toSpecific
import org.apache.avro.specific.SpecificData
import org.apache.avro.specific.SpecificRecord
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DatumReader
import org.apache.avro.io.DatumWriter
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import java.io.ByteArrayOutputStream
import com.github.avrokotlin.benchmark.internal.apache.Clients as ApacheClients
import com.github.avrokotlin.benchmark.internal.apache.ListWrapperDatasClass as ApacheListWrapperDatasClass
import com.github.avrokotlin.benchmark.internal.apache.SimpleDatasClass as ApacheSimpleDatasClass
import com.github.avrokotlin.benchmark.internal.jackson.Clients as JacksonClients
import com.github.avrokotlin.benchmark.internal.specific.Clients as SpecificClients
import com.github.avrokotlin.benchmark.internal.specific.ListWrapperDatasClass as SpecificListWrapperDatasClass
import com.github.avrokotlin.benchmark.internal.specific.SimpleDatasClass as SpecificSimpleDatasClass

/**
 * The per-workload wiring of [EquivalenceGate]: every benchmark base class calls the gate for its
 * workload from `@Setup`, so the comparison is proven once per trial, outside anything measured, and
 * for every benchmark class rather than only for the one library that happens to be running.
 *
 * Every library is built here through the same factory the benchmarks use
 * ([ApacheAvro.reflectData], [JacksonAvro.mapper]), so what is verified is what is measured.
 */
internal object WorkloadEquivalence {
    fun verifyComplex(clients: Clients) {
        val coreSchema = Avro.coreSchema<Clients>()
        val canonicalSchema = coreSchema.asApacheSchema()
        val canonicalBytes = Avro.encodeWith(coreSchema, clients)
        EquivalenceGate.verify(
            workload = "complex",
            canonicalSchema = canonicalSchema,
            canonicalBytes = canonicalBytes,
            libraries = listOf(
                ComparedLibrary(
                    label = "avro4k",
                    schema = canonicalSchema,
                    encode = { Avro.encodeWith(coreSchema, clients) },
                    decodeAndReEncode = {
                        Avro.encodeWith(coreSchema, Avro.decodeWith<Clients>(coreSchema, it))
                    },
                ),
                apacheLibrary(ApacheClients::class.java, clients.toApache()),
                apacheGenericLibrary(canonicalSchema, canonicalBytes, GENERIC_MAP_ORDER),
                apacheSpecificLibrary(SpecificClients::class.java, clients.toSpecific(), SPECIFIC_MAP_ORDER),
                jacksonLibrary(
                    canonicalSchema = canonicalSchema,
                    modelClass = JacksonClients::class.java,
                    model = clients.toJackson(),
                    namespaceAliases = JacksonAvro.NAMESPACE_ALIASES,
                    schemaExemptions = JACKSON_COMPLEX_SCHEMA_EXEMPTIONS,
                    byteExemption = JACKSON_MAP_ORDER,
                ),
            ),
        )
    }

    fun verifySimple(data: SimpleDatasClass) {
        val coreSchema = Avro.coreSchema<SimpleDatasClass>()
        val canonicalSchema = coreSchema.asApacheSchema()
        val canonicalBytes = Avro.encodeWith(coreSchema, data)
        EquivalenceGate.verify(
            workload = "simple",
            canonicalSchema = canonicalSchema,
            canonicalBytes = canonicalBytes,
            libraries = listOf(
                ComparedLibrary(
                    label = "avro4k",
                    schema = canonicalSchema,
                    encode = { Avro.encodeWith(coreSchema, data) },
                    decodeAndReEncode = {
                        Avro.encodeWith(
                            coreSchema,
                            Avro.decodeWith<SimpleDatasClass>(coreSchema, it),
                        )
                    },
                ),
                apacheLibrary(ApacheSimpleDatasClass::class.java, data.toApache()),
                apacheGenericLibrary(canonicalSchema, canonicalBytes),
                apacheSpecificLibrary(SpecificSimpleDatasClass::class.java, data.toSpecific()),
                // Jackson round-trips avro4k's own model here, so there is no Jackson copy to compare.
                jacksonLibrary(canonicalSchema, SimpleDatasClass::class.java, data),
            ),
        )
    }

    fun verifyLists(data: ListWrapperDatasClass) {
        val coreSchema = Avro.coreSchema<ListWrapperDatasClass>()
        val canonicalSchema = coreSchema.asApacheSchema()
        val canonicalBytes = Avro.encodeWith(coreSchema, data)
        EquivalenceGate.verify(
            workload = "lists",
            canonicalSchema = canonicalSchema,
            canonicalBytes = canonicalBytes,
            libraries = listOf(
                ComparedLibrary(
                    label = "avro4k",
                    schema = canonicalSchema,
                    encode = { Avro.encodeWith(coreSchema, data) },
                    decodeAndReEncode = {
                        Avro.encodeWith(
                            coreSchema,
                            Avro.decodeWith<ListWrapperDatasClass>(coreSchema, it),
                        )
                    },
                ),
                apacheLibrary(ApacheListWrapperDatasClass::class.java, data.toApache()),
                apacheGenericLibrary(canonicalSchema, canonicalBytes),
                apacheSpecificLibrary(SpecificListWrapperDatasClass::class.java, data.toSpecific()),
                jacksonLibrary(canonicalSchema, ListWrapperDatasClass::class.java, data),
            ),
        )
    }

    /**
     * Apache encodes with the schema `ReflectData` derives from its own model — not with avro4k's.
     * It has to: that schema carries the `java-class` hints (`"[B"`, `java.lang.Byte`,
     * `java.lang.Character`, …) that tell `ReflectData` which Java type to put back into each field.
     * Handed avro4k's hint-free schema it decodes a `GenericData$Array` into an `Array<String>` field
     * and an `Integer` into a `Byte` one, which is precisely how the two `read` benchmarks used to
     * die in `@Setup`. Those hints do not reach the wire; the gate proves the rest of the schema, and
     * the bytes, are avro4k's.
     */
    private fun <T : Any> apacheLibrary(modelClass: Class<T>, model: T): ComparedLibrary {
        val reflectData = ApacheAvro.reflectData()
        val schema = reflectData.getSchema(modelClass)

        @Suppress("UNCHECKED_CAST")
        val writer = reflectData.createDatumWriter(schema) as DatumWriter<T>

        @Suppress("UNCHECKED_CAST")
        val reader = reflectData.createDatumReader(schema) as DatumReader<T>
        return ComparedLibrary(
            label = "apache",
            schema = schema,
            namespaceAliases = ApacheAvro.NAMESPACE_ALIASES,
            encode = { encodeWithApache(writer, model) },
            decodeAndReEncode = { bytes ->
                encodeWithApache(writer, reader.read(null, DecoderFactory.get().binaryDecoder(bytes, null)))
            },
        )
    }

    /**
     * Apache's *generic* read path, which is the only Apache data model the fast reader applies to
     * (`FastReaderBuilder.isSupportedData` is an exact-class check that excludes `ReflectData` - see
     * [ApacheAvro]). Both readers - fast and slow - are proven here, in one entry:
     *
     * - the schema is avro4k's canonical schema, **unchanged**: generic reading derives nothing from
     *   a class, so there is nothing to diverge;
     * - `encode` re-encodes what the **slow** reader decoded, so the slow path is round-tripped;
     * - `decodeAndReEncode` re-encodes what the **fast** reader decoded, so the fast path is;
     * - and before either, the two readers are required to decode avro4k's bytes to the *same*
     *   datum. A fast reader that is merely fast at producing the wrong answer is the failure mode
     *   this whole unit has to rule out.
     *
     * [ApacheAvro.assertFastReaderInEffect] additionally proves each reader really dispatches to the
     * path its label claims, so "the fast reader agrees with the slow one" cannot be satisfied by
     * two slow readers.
     */
    private fun apacheGenericLibrary(
        canonicalSchema: Schema,
        canonicalBytes: ByteArray,
        byteExemption: ByteExemption? = null,
    ): ComparedLibrary {
        val slowReader = ApacheAvro.genericDatumReader(canonicalSchema, fastReader = false)
        val fastReader = ApacheAvro.genericDatumReader(canonicalSchema, fastReader = true)
        ApacheAvro.assertFastReaderInEffect(slowReader, canonicalBytes, fastReader = false)
        ApacheAvro.assertFastReaderInEffect(fastReader, canonicalBytes, fastReader = true)

        fun decode(reader: DatumReader<GenericRecord>, bytes: ByteArray): GenericRecord =
            reader.read(null, DecoderFactory.get().binaryDecoder(bytes, null))

        val slowDatum = decode(slowReader, canonicalBytes)
        val fastDatum = decode(fastReader, canonicalBytes)
        if (slowDatum != fastDatum) {
            throw AssertionError(
                "[apache-generic] Apache's fast reader and its default reader decode avro4k's bytes to\n" +
                    "  different data, so the two benchmark rows are not measuring the same job.\n" +
                    "    default: $slowDatum\n" +
                    "    fast   : $fastDatum"
            )
        }

        val writer = GenericDatumWriter<GenericRecord>(canonicalSchema, ApacheAvro.genericData(fastReader = false))
        return ComparedLibrary(
            label = "apache-generic",
            schema = canonicalSchema,
            byteExemption = byteExemption,
            encode = { encodeWithApache(writer, slowDatum) },
            decodeAndReEncode = { bytes -> encodeWithApache(writer, decode(fastReader, bytes)) },
        )
    }

    /**
     * Apache's **specific** data model: generated `SpecificRecord` classes, the fastest thing Apache
     * offers and what performance-minded Apache users deploy.
     *
     * The schema compared here is the generated class' own `SCHEMA$`, which is what makes this entry
     * the drift detector for the whole codegen chain: `SCHEMA$` is baked into the generated Java at
     * codegen time from the checked-in `.avsc`, so if that file falls behind `Avro.schema<T>()` the
     * comparison fails - in `@Setup`, on every `apache-specific` trial, naming the exact path that
     * differs. Nothing else needs to police the `.avsc`.
     *
     * The writer and the reader are built by [ApacheAvro.specificDatumWriter] /
     * [ApacheAvro.specificDatumReader], i.e. by the one-argument constructors a real user calls, so
     * what the gate proves is what the benchmarks measure.
     */
    private fun <T : SpecificRecord> apacheSpecificLibrary(
        recordClass: Class<T>,
        model: T,
        byteExemption: ByteExemption? = null,
    ): ComparedLibrary {
        val writer = ApacheAvro.specificDatumWriter(recordClass)
        val reader = ApacheAvro.specificDatumReader(recordClass)
        return ComparedLibrary(
            label = "apache-specific",
            schema = SpecificData.getForClass(recordClass).getSchema(recordClass),
            namespaceAliases = ApacheAvro.SPECIFIC_NAMESPACE_ALIASES,
            byteExemption = byteExemption,
            encode = { encodeWithApache(writer, model) },
            decodeAndReEncode = { bytes ->
                encodeWithApache(writer, reader.read(null, DecoderFactory.get().binaryDecoder(bytes, null)))
            },
        )
    }

    private fun <T : Any> encodeWithApache(writer: DatumWriter<T>, model: T): ByteArray {
        val out = ByteArrayOutputStream()
        val encoder = EncoderFactory.get().directBinaryEncoder(out, null)
        writer.write(model, encoder)
        encoder.flush()
        return out.toByteArray()
    }

    /** Jackson is handed the canonical schema, exactly as the benchmarks hand it one. */
    private fun <T : Any> jacksonLibrary(
        canonicalSchema: Schema,
        modelClass: Class<T>,
        model: T,
        namespaceAliases: Map<String, String> = emptyMap(),
        schemaExemptions: List<SchemaExemption> = emptyList(),
        byteExemption: ByteExemption? = null,
    ): ComparedLibrary {
        val mapper = JacksonAvro.mapper()
        val writer = mapper.writer(AvroSchema(canonicalSchema)).forType(modelClass)
        val reader = mapper.reader(AvroSchema(canonicalSchema)).forType(modelClass)
        fun encode(value: Any): ByteArray {
            val out = ByteArrayOutputStream()
            writer.writeValue(out, value)
            return out.toByteArray()
        }
        return ComparedLibrary(
            label = "jackson",
            // Jackson's own generated schema, which is *not* what it encodes with here. Comparing it
            // is what proves Jackson's model describes the same record; the exemptions below record,
            // precisely, where Jackson's schema generator is less expressive than avro4k's.
            schema = mapper.schemaFor(modelClass).avroSchema,
            namespaceAliases = namespaceAliases,
            schemaExemptions = schemaExemptions,
            byteExemption = byteExemption,
            encode = { encode(model) },
            decodeAndReEncode = { bytes -> encode(reader.readValue<Any>(bytes)) },
        )
    }

    /**
     * Jackson's schema *generator* cannot express three logical types and cannot express a nullable
     * array element. None of this affects what it encodes — it is handed avro4k's schema and writes
     * exactly those bytes — but it does mean `AvroMapper.schemaFor` is not a drop-in replacement for
     * `Avro.schema`, which is worth knowing before anyone relies on it.
     */
    private val JACKSON_COMPLEX_SCHEMA_EXEMPTIONS = listOf(
        SchemaExemption(
            "Clients.clients[].gender<1>",
            "Jackson has no notion of avro4k's `char` logical type; it generates a plain `int`.",
        ),
        SchemaExemption(
            "Clients.clients[].registered<1>",
            "AvroJavaTimeModule encodes a LocalDate as an `int` but generates no `date` logical type.",
        ),
        SchemaExemption(
            "Clients.clients[].tags[]",
            "Jackson erases List<String?> to an array of non-null `string`; it still writes the " +
                "nullable-element union correctly when handed avro4k's schema.",
        ),
        SchemaExemption(
            "Clients.clients[].partner",
            "The property is declared `Any?` - the only shape Jackson will read an Avro union into " +
                "(see JacksonClients) - so its generated schema has `java.lang.Object` where avro4k " +
                "has the three branches. The branches' `timestamp-millis` is not compared for the " +
                "same reason; Jackson does not generate that logical type either.",
        ),
    )

    private val GENERIC_MAP_ORDER = ByteExemption(
        "`GenericData.newMap` returns a `java.util.HashMap`, so a generically decoded Avro map comes " +
            "back in hash order rather than in the order avro4k wrote it. Overriding `newMap` is how " +
            "the `ReflectData` side fixes this, but it cannot be done here: " +
            "`FastReaderBuilder.isSupportedData` is an exact-class check, so a `GenericData` subclass " +
            "silently loses the fast reader - which is the entire point of this variant. Only the " +
            "`complex` workload has a map field; its ten entries come out in a different order, so the " +
            "byte check falls back to proving both encodings decode to the same data."
    )

    private val SPECIFIC_MAP_ORDER = ByteExemption(
        "A generated `SpecificRecord` decodes its map field through `GenericData.newMap`, which " +
            "returns a `java.util.HashMap`, so the ten entries of `Client.map` come back in hash " +
            "order rather than in the order avro4k wrote them. The `ReflectData` side fixes this by " +
            "overriding `newMap`; that is not available here, because the generated classes carry " +
            "their own `MODEL\$` and `FastReaderBuilder.isSupportedData` is an exact-class check - a " +
            "`SpecificData` subclass silently loses the fast reader, which is most of the point of " +
            "this variant. Only the `complex` workload has a map field, so `simple` and `lists` stay " +
            "under strict byte identity; the check falls back to proving both encodings decode to " +
            "the same data."
    )

    private val JACKSON_MAP_ORDER = ByteExemption(
        "jackson-dataformat-avro buffers every Avro map into a `java.util.HashMap` " +
            "(`ser.MapWriteContext._data`), which loses the insertion order avro4k writes. There is no " +
            "setting for it. Only the `complex` workload has a map field; its ten entries come out in a " +
            "different order, so the byte check falls back to proving both encodings decode to the same " +
            "data.",
    )
}
