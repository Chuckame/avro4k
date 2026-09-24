package com.github.avrokotlin.benchmark.complex

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
 * Apache's **generic** read path, measured with its fast reader on and off.
 *
 * Apache 1.12 ships a second read path - `FastReaderBuilder`, which pre-compiles a schema into a
 * tree of closures rather than walking it per datum - and `GenericDatumReader.read` uses it whenever
 * `data.isFastReaderEnabled()`. It exists for `GenericData` and `SpecificData` **only**:
 * `FastReaderBuilder.isSupportedData` is an exact-class check, so `ReflectData` can never have it
 * and the three `ApacheAvroReflect*` benchmarks have no fast variant to offer. See [ApacheAvro] and
 * docs/plans/notes/a6.md.
 *
 * This is deliberately a class of its own rather than a `fastReader` `@Param` on
 * [ApacheAvroReflectBenchmark]: on that class the parameter could not do anything, and a row
 * labelled `fastReader=true` that silently ran the slow path is worse than no row at all.
 *
 * Read-only on purpose - the fast reader is a read-path feature, and a generic *write* row would be
 * a different unit's comparison. What this class measures is therefore not comparable to the
 * `ReflectData` rows: generic decoding produces `GenericRecord`s and `Utf8`s, not the model objects
 * avro4k and `ReflectData` build. The number it is here for is the fast-on / fast-off ratio
 * *within* this class.
 */
internal class ApacheAvroGenericFastReaderBenchmark : SerializationBenchmark() {
    @Param("false", "true")
    @JvmField
    final var fastReader: Boolean = false

    lateinit var reader: DatumReader<GenericRecord>
    lateinit var data: ByteArray

    override fun setup() {
        reader = ApacheAvro.genericDatumReader(schema.asApacheSchema(), fastReader)
    }

    override fun prepareBinaryData() {
        data = Avro.encodeWith(schema, clients)
        // Proves on this exact reader that it dispatches to the path `fastReader` claims, before a
        // single measured read happens.
        ApacheAvro.assertFastReaderInEffect(reader, data, fastReader)
    }

    @Benchmark
    fun read(): GenericRecord {
        val decoder = DecoderFactory.get().binaryDecoder(data, null)
        return reader.read(null, decoder)
    }
}
