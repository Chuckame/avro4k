package com.github.avrokotlin.benchmark.complex

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.benchmark.internal.apache.ApacheAvro
import com.github.avrokotlin.benchmark.internal.apache.toApache
import com.github.avrokotlin.benchmark.internal.encodeWith
import kotlinx.benchmark.Benchmark
import org.apache.avro.Schema
import org.apache.avro.io.BinaryDecoder
import org.apache.avro.io.BinaryEncoder
import org.apache.avro.io.DatumReader
import org.apache.avro.io.DatumWriter
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.Encoder
import org.apache.avro.io.EncoderFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import com.github.avrokotlin.benchmark.internal.apache.Clients as ApacheClients

internal class ApacheAvroReflectBenchmark : SerializationBenchmark() {
    lateinit var writer: DatumWriter<ApacheClients>
    lateinit var encoder: Encoder

    /** Reset before each [writeToByteArray], so it only grows during the first iterations. */
    val byteArrayOutput = ByteArrayOutputStream()

    /** Apache's buffered encoder over [byteArrayOutput], created once as a reused encoder would be. */
    lateinit var byteArrayEncoder: BinaryEncoder

    lateinit var reader: DatumReader<ApacheClients>

    /** The decoder [readFromStream] hands back to the factory on the next call, as Apache users reuse theirs. */
    var streamDecoder: BinaryDecoder? = null

    /**
     * Apache's own model, converted once here so no measured method pays for it.
     * See [com.github.avrokotlin.benchmark.internal.apache.Clients] for why it exists.
     */
    lateinit var model: ApacheClients

    /**
     * The schema `ReflectData` derives from Apache's model, proven equivalent to avro4k's by the
     * equivalence gate. It is not avro4k's: the `java-class` hints it carries are what let
     * `ReflectData` put a `byte[]`, a `Char` or an `Array<String>` back into the right field on read.
     */
    lateinit var apacheSchema: Schema

    lateinit var data: ByteArray

    override fun setup() {
        val reflectData = ApacheAvro.reflectData()
        model = clients.toApache()
        apacheSchema = reflectData.getSchema(ApacheClients::class.java)

        @Suppress("UNCHECKED_CAST")
        writer = reflectData.createDatumWriter(apacheSchema) as DatumWriter<ApacheClients>
        // Allocated once so that `write` only measures the encoding, not the sink construction.
        encoder = EncoderFactory.get().directBinaryEncoder(OutputStream.nullOutputStream(), null)
        byteArrayEncoder = EncoderFactory.get().binaryEncoder(byteArrayOutput, null)

        @Suppress("UNCHECKED_CAST")
        reader = reflectData.createDatumReader(apacheSchema) as DatumReader<ApacheClients>
    }

    override fun prepareBinaryData() {
        data = Avro.encodeWith(schema, clients)
    }

    @Benchmark
    fun read(): ApacheClients {
        val decoder = DecoderFactory.get().binaryDecoder(data, null)
        return reader.read(null, decoder)
    }

    /** The reference for avro4k's stream reads: Apache's buffered decoder over a stream created per call. */
    @Benchmark
    fun readFromStream(): ApacheClients {
        val decoder = DecoderFactory.get().binaryDecoder(ByteArrayInputStream(data), streamDecoder)
        streamDecoder = decoder
        return reader.read(null, decoder)
    }

    @Benchmark
    fun write() {
        writer.write(model, encoder)
        encoder.flush()
    }

    /** The reference for avro4k's `encodeToByteArray`: the encoded bytes copied into a new array. */
    @Benchmark
    fun writeToByteArray(): ByteArray {
        byteArrayOutput.reset()
        writer.write(model, byteArrayEncoder)
        byteArrayEncoder.flush()
        return byteArrayOutput.toByteArray()
    }
}
