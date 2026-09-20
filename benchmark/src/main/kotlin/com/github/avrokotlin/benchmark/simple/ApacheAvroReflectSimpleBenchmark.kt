package com.github.avrokotlin.benchmark.simple

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.benchmark.internal.apache.ApacheAvro
import com.github.avrokotlin.benchmark.internal.apache.toApache
import kotlinx.benchmark.Benchmark
import org.apache.avro.Schema
import org.apache.avro.io.DatumReader
import org.apache.avro.io.DatumWriter
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.Encoder
import org.apache.avro.io.EncoderFactory
import java.io.OutputStream
import com.github.avrokotlin.benchmark.internal.apache.SimpleDatasClass as ApacheSimpleDatasClass

internal class ApacheAvroReflectSimpleBenchmark : SerializationSimpleBenchmark() {
    lateinit var writer: DatumWriter<ApacheSimpleDatasClass>
    lateinit var encoder: Encoder
    lateinit var reader: DatumReader<ApacheSimpleDatasClass>

    /** Apache's own model, converted once here so no measured method pays for it. */
    lateinit var model: ApacheSimpleDatasClass

    /**
     * The schema `ReflectData` derives from Apache's model, proven equivalent to avro4k's by the
     * equivalence gate. Its `java-class` hints are what narrow the decoded `Integer` back to the
     * `Byte` and `Short` fields — handed avro4k's hint-free schema, this benchmark's `read` threw.
     */
    lateinit var apacheSchema: Schema

    lateinit var data: ByteArray

    override fun setup() {
        val reflectData = ApacheAvro.reflectData()
        model = clients.toApache()
        apacheSchema = reflectData.getSchema(ApacheSimpleDatasClass::class.java)

        @Suppress("UNCHECKED_CAST")
        writer = reflectData.createDatumWriter(apacheSchema) as DatumWriter<ApacheSimpleDatasClass>
        // Allocated once so that `write` only measures the encoding, not the sink construction.
        encoder = EncoderFactory.get().directBinaryEncoder(OutputStream.nullOutputStream(), null)

        @Suppress("UNCHECKED_CAST")
        reader = reflectData.createDatumReader(apacheSchema) as DatumReader<ApacheSimpleDatasClass>
    }

    override fun prepareBinaryData() {
        data = Avro.encodeToByteArray(schema, clients)
    }

    @Benchmark
    fun read(): ApacheSimpleDatasClass {
        val decoder = DecoderFactory.get().binaryDecoder(data, null)
        return reader.read(null, decoder)
    }

    @Benchmark
    fun write() {
        writer.write(model, encoder)
        encoder.flush()
    }
}
