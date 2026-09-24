package com.github.avrokotlin.benchmark.complex

import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.dataformat.avro.AvroSchema
import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.benchmark.internal.asApacheSchema
import com.github.avrokotlin.benchmark.internal.encodeWith
import com.github.avrokotlin.benchmark.internal.jackson.JacksonAvro
import com.github.avrokotlin.benchmark.internal.jackson.toJackson
import kotlinx.benchmark.Benchmark
import java.io.OutputStream
import com.github.avrokotlin.benchmark.internal.jackson.Clients as JacksonClients


internal class JacksonAvroBenchmark : SerializationBenchmark() {
    lateinit var writer: ObjectWriter
    lateinit var reader: ObjectReader

    /**
     * Jackson's own model, converted once here so no measured method pays for it.
     * See [com.github.avrokotlin.benchmark.internal.jackson.Clients] for why it exists.
     */
    lateinit var model: JacksonClients

    lateinit var data: ByteArray

    /**
     * Allocated once so that [write] only measures the encoding, not the sink construction.
     * The mapper has `AUTO_CLOSE_TARGET` disabled so the stream stays usable across iterations.
     */
    lateinit var out: OutputStream

    override fun setup() {
        val mapper = JacksonAvro.mapper()
        model = clients.toJackson()
        writer = mapper.writer(AvroSchema(schema.asApacheSchema())).forType(JacksonClients::class.java)
        reader = mapper.reader(AvroSchema(schema.asApacheSchema())).forType(JacksonClients::class.java)
        out = OutputStream.nullOutputStream()
    }

    override fun prepareBinaryData() {
        data = Avro.encodeWith(schema, clients)
    }

    @Benchmark
    fun read(): JacksonClients = reader.readValue<JacksonClients>(data)

    @Benchmark
    fun write() {
        writer.writeValue(out, model)
    }
}
