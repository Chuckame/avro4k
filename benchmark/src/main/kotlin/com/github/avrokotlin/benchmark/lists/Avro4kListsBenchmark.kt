package com.github.avrokotlin.benchmark.lists

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.avro4k.encodeToSink
import com.github.avrokotlin.benchmark.internal.ListWrapperDatasClass
import com.github.avrokotlin.benchmark.internal.encodeWith
import kotlinx.benchmark.Benchmark
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.OutputStream

internal class Avro4kListsBenchmark : SerializationListsBenchmark() {
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
        data = Avro.encodeWith(schema, lists)
    }

    @Benchmark
    fun read(): ListWrapperDatasClass = Avro.decodeFromByteArray<ListWrapperDatasClass>(schema, data)

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun write() {
        Avro.encodeToSink(schema, lists, sink)
        sink.flush()
    }
}
