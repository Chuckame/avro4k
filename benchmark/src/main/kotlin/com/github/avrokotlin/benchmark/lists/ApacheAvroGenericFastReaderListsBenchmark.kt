package com.github.avrokotlin.benchmark.lists

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.benchmark.internal.apache.ApacheAvro
import com.github.avrokotlin.benchmark.internal.asApacheSchema
import com.github.avrokotlin.benchmark.internal.encodeWith
import kotlinx.benchmark.Benchmark
import kotlinx.benchmark.Param
import org.apache.avro.generic.GenericRecord
import org.apache.avro.io.DatumReader
import org.apache.avro.io.DecoderFactory

/**
 * Apache's **generic** read path on the `lists` workload, with its fast reader on and off.
 * See [com.github.avrokotlin.benchmark.complex.ApacheAvroGenericFastReaderBenchmark] for why this is
 * a separate class, why it is read-only, and why its absolute numbers are not comparable to the
 * `ReflectData` rows.
 */
internal class ApacheAvroGenericFastReaderListsBenchmark : SerializationListsBenchmark() {
    @Param("false", "true")
    @JvmField
    final var fastReader: Boolean = false

    lateinit var reader: DatumReader<GenericRecord>
    lateinit var data: ByteArray

    override fun setup() {
        reader = ApacheAvro.genericDatumReader(schema.asApacheSchema(), fastReader)
    }

    override fun prepareBinaryData() {
        data = Avro.encodeWith(schema, lists)
        ApacheAvro.assertFastReaderInEffect(reader, data, fastReader)
    }

    @Benchmark
    fun read(): GenericRecord {
        val decoder = DecoderFactory.get().binaryDecoder(data, null)
        return reader.read(null, decoder)
    }
}
