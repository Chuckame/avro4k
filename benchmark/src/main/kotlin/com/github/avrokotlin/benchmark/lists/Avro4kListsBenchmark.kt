package com.github.avrokotlin.benchmark.lists

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.avro4k.decodeFromSource
import com.github.avrokotlin.avro4k.decodeFromStream
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.avro4k.encodeToSink
import com.github.avrokotlin.avro4k.encodeToStream
import com.github.avrokotlin.benchmark.internal.ListWrapperDatasClass
import com.github.avrokotlin.benchmark.internal.encodeWith
import kotlinx.benchmark.Benchmark
import kotlinx.io.Sink
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.ExperimentalSerializationApi
import java.io.ByteArrayInputStream
import java.io.OutputStream

internal class Avro4kListsBenchmark : SerializationListsBenchmark() {
    lateinit var data: ByteArray

    /**
     * Allocated once so that [write] only measures the encoding, not the sink construction.
     * Flushed at the end of each iteration so it cannot grow across iterations.
     */
    lateinit var sink: Sink

    /** Discards what [writeToStream] writes. Created once: a caller's stream outlives the values written to it. */
    val output: OutputStream = OutputStream.nullOutputStream()

    override fun setup() {
        sink = OutputStream.nullOutputStream().asSink().buffered()
    }

    override fun prepareBinaryData() {
        data = Avro.encodeWith(schema, lists)
    }

    @Benchmark
    fun read(): ListWrapperDatasClass = Avro.decodeFromByteArray<ListWrapperDatasClass>(schema, data)

    /** The same bytes read from a stream: through a kotlinx-io `Source`, segment by segment, as from a file. */
    @Benchmark
    fun readFromSource(): ListWrapperDatasClass = Avro.decodeFromSource<ListWrapperDatasClass>(schema, ByteArrayInputStream(data).asSource().buffered())

    /** One value read from a stream created per call, as from a file or a request body. */
    @Benchmark
    fun readFromStream(): ListWrapperDatasClass = Avro.decodeFromStream<ListWrapperDatasClass>(schema, ByteArrayInputStream(data))

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun write() {
        Avro.encodeToSink(schema, lists, sink)
        sink.flush()
    }

    /** One value written to a caller's stream. */
    @Benchmark
    fun writeToStream() {
        Avro.encodeToStream(schema, lists, output)
    }

    /** A new array holding the encoded bytes, as most callers want them: to send, store or wrap in a message. */
    @Benchmark
    fun writeToByteArray(): ByteArray = Avro.encodeToByteArray(schema, lists)
}
