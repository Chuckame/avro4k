package com.github.avrokotlin.benchmark.simple

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.avro4k.encodeToSink
import com.github.avrokotlin.benchmark.internal.SimpleDatasClass
import kotlinx.benchmark.Benchmark
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.OutputStream

internal class Avro4kSimpleBenchmark : SerializationSimpleBenchmark() {
    lateinit var data: ByteArray

    /**
     * Allocated once so that [write] only measures the encoding, not the sink construction.
     * Flushed at the end of each iteration so it cannot grow across iterations.
     */
    lateinit var sink: Sink

    override fun setup() {
        sink = OutputStream.nullOutputStream().asSink().buffered()
    }

    override fun prepareBinaryData() {
        data = Avro.encodeToByteArray(schema, clients)
    }

    @Benchmark
    fun read(): SimpleDatasClass = Avro.decodeFromByteArray<SimpleDatasClass>(schema, data)

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun write() {
        Avro.encodeToSink(schema, clients, sink)
        sink.flush()
    }
}
