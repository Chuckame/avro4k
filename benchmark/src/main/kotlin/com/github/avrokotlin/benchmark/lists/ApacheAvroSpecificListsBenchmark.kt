package com.github.avrokotlin.benchmark.lists

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
import com.github.avrokotlin.benchmark.internal.specific.ListWrapperDatasClass as SpecificListWrapperDatasClass

/**
 * The `lists` workload through Apache's specific data model.
 * See [com.github.avrokotlin.benchmark.complex.ApacheAvroSpecificBenchmark] for why it is here.
 */
internal class ApacheAvroSpecificListsBenchmark : SerializationListsBenchmark() {
    lateinit var writer: DatumWriter<SpecificListWrapperDatasClass>
    lateinit var encoder: Encoder
    lateinit var reader: DatumReader<SpecificListWrapperDatasClass>

    /** The generated model, converted once here so no measured method pays for it. */
    lateinit var model: SpecificListWrapperDatasClass

    lateinit var data: ByteArray

    override fun setup() {
        model = lists.toSpecific()
        writer = ApacheAvro.specificDatumWriter(SpecificListWrapperDatasClass::class.java)
        // Allocated once so that `write` only measures the encoding, not the sink construction.
        encoder = EncoderFactory.get().directBinaryEncoder(OutputStream.nullOutputStream(), null)
        reader = ApacheAvro.specificDatumReader(SpecificListWrapperDatasClass::class.java)
    }

    override fun prepareBinaryData() {
        data = Avro.encodeWith(schema, lists)
        ApacheAvro.assertFastReaderInEffect(reader, data, fastReader = true)
    }

    @Benchmark
    fun read(): SpecificListWrapperDatasClass {
        val decoder = DecoderFactory.get().binaryDecoder(data, null)
        return reader.read(null, decoder)
    }

    @Benchmark
    fun write() {
        writer.write(model, encoder)
        encoder.flush()
    }
}
