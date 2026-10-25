package com.github.avrokotlin.benchmark.lists

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.avro4k.decodeFromSource
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.avro4k.encodeToSink
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

    /**
     * One value read from a stream created per call, as from a file or a request body: through the kotlinx-io adapters
     * a caller holding an `InputStream` uses today.
     */
    @Benchmark
    fun readFromStream(): ListWrapperDatasClass = Avro.decodeFromSource<ListWrapperDatasClass>(schema, ByteArrayInputStream(data).asSource().buffered())

    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun write() {
        Avro.encodeToSink(schema, lists, sink)
        sink.flush()
    }

    /**
     * One value written to a caller's stream: through the kotlinx-io adapters, the sink created per call and flushed so
     * that every byte reaches the stream.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Benchmark
    fun writeToStream() {
        val streamSink = output.asSink().buffered()
        Avro.encodeToSink(schema, lists, streamSink)
        streamSink.flush()
    }

    /** A new array holding the encoded bytes, as most callers want them: to send, store or wrap in a message. */
    @Benchmark
    fun writeToByteArray(): ByteArray = Avro.encodeToByteArray(schema, lists)
}
