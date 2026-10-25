package com.github.avrokotlin.benchmark.simple

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromByteArray
import com.github.avrokotlin.avro4k.decodeFromStream
import com.github.avrokotlin.avro4k.encodeToByteArray
import com.github.avrokotlin.avro4k.encodeToStream
import com.github.avrokotlin.benchmark.internal.SimpleDatasClass
import com.github.avrokotlin.benchmark.internal.encodeWith
import kotlinx.benchmark.Benchmark
import java.io.ByteArrayInputStream
import java.io.OutputStream

internal class Avro4kSimpleBenchmark : SerializationSimpleBenchmark() {
    lateinit var data: ByteArray

    /** Discards what [writeToStream] writes. Created once: a caller's stream outlives the values written to it. */
    val output: OutputStream = OutputStream.nullOutputStream()

    override fun setup() {
    }

    override fun prepareBinaryData() {
        data = Avro.encodeWith(schema, clients)
    }

    @Benchmark
    fun read(): SimpleDatasClass = Avro.decodeFromByteArray<SimpleDatasClass>(schema, data)

    /** One value read from a stream created per call, as from a file or a request body. */
    @Benchmark
    fun readFromStream(): SimpleDatasClass = Avro.decodeFromStream<SimpleDatasClass>(schema, ByteArrayInputStream(data))

    /** One value written to a caller's stream. */
    @Benchmark
    fun writeToStream() {
        Avro.encodeToStream(schema, clients, output)
    }

    /** A new array holding the encoded bytes, as most callers want them: to send, store or wrap in a message. */
    @Benchmark
    fun writeToByteArray(): ByteArray = Avro.encodeToByteArray(schema, clients)
}
