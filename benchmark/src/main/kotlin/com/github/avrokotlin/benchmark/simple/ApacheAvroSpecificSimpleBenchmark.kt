package com.github.avrokotlin.benchmark.simple

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.benchmark.internal.apache.ApacheAvro
import com.github.avrokotlin.benchmark.internal.encodeWith
import com.github.avrokotlin.benchmark.internal.specific.toSpecific
import kotlinx.benchmark.Benchmark
import org.apache.avro.io.DatumReader
import org.apache.avro.io.DatumWriter
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.Encoder
import org.apache.avro.io.EncoderFactory
import java.io.OutputStream
import com.github.avrokotlin.benchmark.internal.specific.SimpleDatasClass as SpecificSimpleDatasClass

/**
 * The `simple` workload through Apache's specific data model.
 * See [com.github.avrokotlin.benchmark.complex.ApacheAvroSpecificBenchmark] for why it is here.
 */
internal class ApacheAvroSpecificSimpleBenchmark : SerializationSimpleBenchmark() {
    lateinit var writer: DatumWriter<SpecificSimpleDatasClass>
    lateinit var encoder: Encoder
    lateinit var reader: DatumReader<SpecificSimpleDatasClass>

    /** The generated model, converted once here so no measured method pays for it. */
    lateinit var model: SpecificSimpleDatasClass

    lateinit var data: ByteArray

    override fun setup() {
        model = clients.toSpecific()
        writer = ApacheAvro.specificDatumWriter(SpecificSimpleDatasClass::class.java)
        // Allocated once so that `write` only measures the encoding, not the sink construction.
        encoder = EncoderFactory.get().directBinaryEncoder(OutputStream.nullOutputStream(), null)
        reader = ApacheAvro.specificDatumReader(SpecificSimpleDatasClass::class.java)
    }

    override fun prepareBinaryData() {
        data = Avro.encodeWith(schema, clients)
        ApacheAvro.assertFastReaderInEffect(reader, data, fastReader = true)
    }

    @Benchmark
    fun read(): SpecificSimpleDatasClass {
        val decoder = DecoderFactory.get().binaryDecoder(data, null)
        return reader.read(null, decoder)
    }

    @Benchmark
    fun write() {
        writer.write(model, encoder)
        encoder.flush()
    }
}
