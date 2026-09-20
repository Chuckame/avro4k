package com.github.avrokotlin.benchmark.complex

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.benchmark.internal.apache.ApacheAvro
import com.github.avrokotlin.benchmark.internal.specific.toSpecific
import kotlinx.benchmark.Benchmark
import org.apache.avro.io.DatumReader
import org.apache.avro.io.DatumWriter
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.Encoder
import org.apache.avro.io.EncoderFactory
import java.io.OutputStream
import com.github.avrokotlin.benchmark.internal.specific.Clients as SpecificClients

/**
 * Apache's **specific** data model: generated `SpecificRecord` classes plus `SpecificData`.
 *
 * This is the fastest thing Apache offers, and the configuration performance-minded Apache users
 * actually deploy - which is exactly why it has to be in the suite. Until A7 avro4k was compared
 * against `ReflectData`, the *slowest* Apache data model and the one Apache excludes from its own
 * fast read path, which flattered the comparison.
 *
 * Unlike the `ApacheAvroGenericFastReader*` classes, this one is directly comparable to the
 * `ApacheAvroReflect*` and `Avro4k*` rows: it decodes into real model objects with real field types
 * (`String`, `LocalDate`, `Instant`, an enum), not `GenericRecord`s and `Utf8`s. The generated
 * classes come from avro4k's own canonical schema - see
 * [com.github.avrokotlin.benchmark.internal.specific.CanonicalSchemas] - and the equivalence gate
 * proves on every trial that their `SCHEMA$` is still avro4k's and that they put avro4k's bytes on
 * the wire.
 *
 * No tuning, no caches, no shared state: the writer and the reader are the one-argument
 * `SpecificDatumWriter(Class)` / `SpecificDatumReader(Class)` constructors, and the data model they
 * pick up is the generated class' own `MODEL$`. The fast reader comes with that for free - it is on
 * by default in 1.12.1 and `SpecificData` is one of the two classes `FastReaderBuilder` accepts -
 * and [ApacheAvro.assertFastReaderInEffect] proves in `@Setup` that the reader really built one.
 */
internal class ApacheAvroSpecificBenchmark : SerializationBenchmark() {
    lateinit var writer: DatumWriter<SpecificClients>
    lateinit var encoder: Encoder
    lateinit var reader: DatumReader<SpecificClients>

    /** The generated model, converted once here so no measured method pays for it. */
    lateinit var model: SpecificClients

    lateinit var data: ByteArray

    override fun setup() {
        model = clients.toSpecific()
        writer = ApacheAvro.specificDatumWriter(SpecificClients::class.java)
        // Allocated once so that `write` only measures the encoding, not the sink construction.
        encoder = EncoderFactory.get().directBinaryEncoder(OutputStream.nullOutputStream(), null)
        reader = ApacheAvro.specificDatumReader(SpecificClients::class.java)
    }

    override fun prepareBinaryData() {
        data = Avro.encodeToByteArray(schema, clients)
        // Proves on this exact reader that the fast reader really engaged, before anything is
        // measured. `isFastReaderEnabled()` alone is not proof - see docs/plans/notes/a6.md.
        ApacheAvro.assertFastReaderInEffect(reader, data, fastReader = true)
    }

    @Benchmark
    fun read(): SpecificClients {
        val decoder = DecoderFactory.get().binaryDecoder(data, null)
        return reader.read(null, decoder)
    }

    @Benchmark
    fun write() {
        writer.write(model, encoder)
        encoder.flush()
    }
}
