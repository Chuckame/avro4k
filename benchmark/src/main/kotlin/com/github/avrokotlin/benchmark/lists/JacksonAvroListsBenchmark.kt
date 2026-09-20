package com.github.avrokotlin.benchmark.lists

import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.dataformat.avro.AvroSchema
import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.benchmark.internal.ListWrapperDatasClass
import com.github.avrokotlin.benchmark.internal.jackson.JacksonAvro
import kotlinx.benchmark.Benchmark
import java.io.OutputStream


/**
 * Jackson reads and writes avro4k's own model for this workload - it round-trips it as-is, and its
 * generated schema for it matches the canonical one - so there is no Jackson copy of the model here.
 */
internal class JacksonAvroListsBenchmark : SerializationListsBenchmark() {
    lateinit var writer: ObjectWriter
    lateinit var reader: ObjectReader

    lateinit var data: ByteArray

    /**
     * Allocated once so that [write] only measures the encoding, not the sink construction.
     * The mapper has `AUTO_CLOSE_TARGET` disabled so the stream stays usable across iterations.
     */
    lateinit var out: OutputStream

    override fun setup() {
        val mapper = JacksonAvro.mapper()
        writer = mapper.writer(AvroSchema(schema)).forType(ListWrapperDatasClass::class.java)
        reader = mapper.reader(AvroSchema(schema)).forType(ListWrapperDatasClass::class.java)
        out = OutputStream.nullOutputStream()
    }

    override fun prepareBinaryData() {
        data = Avro.encodeToByteArray(schema, lists)
    }

    @Benchmark
    fun read(): ListWrapperDatasClass = reader.readValue<ListWrapperDatasClass>(data)

    @Benchmark
    fun write() {
        writer.writeValue(out, lists)
    }
}
