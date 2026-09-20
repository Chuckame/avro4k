package com.github.avrokotlin.benchmark.lists

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
import com.github.avrokotlin.benchmark.internal.apache.ListWrapperDatasClass as ApacheListWrapperDatasClass

internal class ApacheAvroReflectListsBenchmark : SerializationListsBenchmark() {
    lateinit var writer: DatumWriter<ApacheListWrapperDatasClass>
    lateinit var encoder: Encoder
    lateinit var reader: DatumReader<ApacheListWrapperDatasClass>

    /** Apache's own model, converted once here so no measured method pays for it. */
    lateinit var model: ApacheListWrapperDatasClass

    /** The schema `ReflectData` derives from Apache's model, proven equivalent to avro4k's. */
    lateinit var apacheSchema: Schema

    lateinit var data: ByteArray

    override fun setup() {
        val reflectData = ApacheAvro.reflectData()
        model = lists.toApache()
        apacheSchema = reflectData.getSchema(ApacheListWrapperDatasClass::class.java)

        @Suppress("UNCHECKED_CAST")
        writer = reflectData.createDatumWriter(apacheSchema) as DatumWriter<ApacheListWrapperDatasClass>
        // Allocated once so that `write` only measures the encoding, not the sink construction.
        encoder = EncoderFactory.get().directBinaryEncoder(OutputStream.nullOutputStream(), null)

        @Suppress("UNCHECKED_CAST")
        reader = reflectData.createDatumReader(apacheSchema) as DatumReader<ApacheListWrapperDatasClass>
    }

    override fun prepareBinaryData() {
        data = Avro.encodeToByteArray(schema, lists)
    }

    @Benchmark
    fun read(): ApacheListWrapperDatasClass {
        val decoder = DecoderFactory.get().binaryDecoder(data, null)
        return reader.read(null, decoder)
    }

    @Benchmark
    fun write() {
        writer.write(model, encoder)
        encoder.flush()
    }
}
