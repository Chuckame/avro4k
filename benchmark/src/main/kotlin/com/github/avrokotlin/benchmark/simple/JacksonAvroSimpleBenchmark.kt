package com.github.avrokotlin.benchmark.simple

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ObjectReader
import com.fasterxml.jackson.databind.ObjectWriter
import com.fasterxml.jackson.dataformat.avro.AvroMapper
import com.fasterxml.jackson.dataformat.avro.AvroSchema
import com.fasterxml.jackson.dataformat.avro.jsr310.AvroJavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.benchmark.internal.SimpleDatasClass
import kotlinx.benchmark.Benchmark
import java.io.OutputStream


internal class JacksonAvroSimpleBenchmark : SerializationSimpleBenchmark() {
    lateinit var writer: ObjectWriter
    lateinit var reader: ObjectReader

    var data: ByteArray? = null

    override fun setup() {
        writer = SimpleDatasClass::class.java.createWriter()
        reader = SimpleDatasClass::class.java.createReader()
    }

    override fun prepareBinaryData() {
        data = Avro.default.encodeToByteArray(SimpleDatasClass.serializer(), clients)
    }

    @Benchmark
    fun read() {
        reader.readValue<SimpleDatasClass>(data)
    }

    @Benchmark
    fun write() {
        writer.writeValue(OutputStream.nullOutputStream(), clients)
    }

    private fun <T> Class<T>.createWriter(): ObjectWriter {
        val mapper = avroMapper()

        return mapper.writer(AvroSchema(schema)).forType(this)
    }

    private fun <T> Class<T>.createReader(): ObjectReader {
        val mapper = avroMapper()

        return mapper.reader(AvroSchema(schema)).forType(this)
    }

    private fun avroMapper(): ObjectMapper = AvroMapper()
        .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .registerKotlinModule()
        .registerModule(AvroJavaTimeModule())
}
