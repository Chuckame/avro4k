package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.fixtures.GoldenFixture
import com.github.avrokotlin.avro4k.fixtures.ResolutionFixture
import com.github.avrokotlin.avro4k.fixtures.allGoldenFixtures
import com.github.avrokotlin.avro4k.fixtures.resolutionFixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

internal class AvroStreamsTest : StringSpec({
    val value = Record(id = 7, name = "alice", tag = byteArrayOf(1, 2, 3, 4), score = 1.5, amounts = listOf(1L, -300L, Long.MAX_VALUE))
    val encoded = Avro.encodeToByteArray(value)

    "writes every byte of a small value to the stream" {
        val small = Small(1, 2)
        val output = ByteArrayOutputStream()

        Avro.encodeToStream(small, output)

        output.toByteArray() shouldBe Avro.encodeToByteArray(small)
        output.size() shouldBe 2
    }

    "writes the same bytes as encodeToByteArray, for a value spanning several of the encoder's chunks" {
        val large = value.copy(name = "x".repeat(300_000))
        val output = ByteArrayOutputStream()

        Avro.encodeToStream(large, output)

        output.toByteArray() shouldBe Avro.encodeToByteArray(large)
    }

    "writes values one after another to the same stream" {
        val other = value.copy(id = 8, name = "bob")
        val output = ByteArrayOutputStream()

        Avro.encodeToStream(value, output)
        Avro.encodeToStream(other, output)

        output.toByteArray() shouldBe encoded + Avro.encodeToByteArray(other)
    }

    "neither flushes nor closes the output stream" {
        val output = RecordingOutputStream()

        Avro.encodeToStream(value, output)

        output.toByteArray() shouldBe encoded
        output.flushed shouldBe false
        output.closed shouldBe false
    }

    "reads the value of a stream" {
        Avro.decodeFromStream<Record>(ByteArrayInputStream(encoded)) shouldBe value
        Avro.decodeFromStream<Record>(Avro.schema<Record>(), ByteArrayInputStream(encoded)) shouldBe value
    }

    "reads a stream that returns one byte per read, every value crossing a refill" {
        Avro.decodeFromStream<Record>(ChunkedInputStream(encoded, 1)) shouldBe value
    }

    "reads values longer than the array refilled from the stream" {
        val large = value.copy(name = "é".repeat(20_000), tag = ByteArray(2_000_000) { it.toByte() }, amounts = List(5_000) { it * 1_000_000L })
        val bytes = Avro.encodeToByteArray(large)

        Avro.decodeFromStream<Record>(ByteArrayInputStream(bytes)) shouldBe large
        Avro.decodeFromStream<Record>(ChunkedInputStream(bytes, 1000)) shouldBe large
        // Says nothing of its size and fills every read: the array starts small and doubles on each refill
        Avro.decodeFromStream<Record>(ChunkedInputStream(bytes, Int.MAX_VALUE)) shouldBe large
    }

    "skips writer-only fields across refills, short and longer than the array" {
        for (extra in listOf("abc", "x".repeat(50_000))) {
            val written = WithExtra(id = 1, extra = extra, extras = listOf(extra, extra), bytes = ByteArray(extra.length), last = 2)
            val bytes = Avro.encodeToByteArray(written)

            for (stream in listOf(ByteArrayInputStream(bytes), ChunkedInputStream(bytes, 1), SkipRefusingInputStream(bytes))) {
                Avro.decodeFromStream<WithoutExtra>(Avro.schema<WithExtra>(), stream) shouldBe WithoutExtra(id = 1, last = 2)
            }
        }
    }

    "fails on a stream holding a second value, with the bytes left in the array" {
        val other = value.copy(id = 8)

        val e = shouldThrow<AvroMalformedDataException> { Avro.decodeFromStream<Record>(ByteArrayInputStream(encoded + Avro.encodeToByteArray(other))) }

        e.message shouldBe "Not all bytes were consumed during deserialization"
    }

    "fails on a stream holding more bytes after a value ending exactly at the end of a refill" {
        // Each read returns at most the value's length, so the value fills the array exactly and only `read` sees the rest
        val stream = ChunkedInputStream(encoded + byteArrayOf(0), encoded.size)

        val e = shouldThrow<AvroMalformedDataException> { Avro.decodeFromStream<Record>(stream) }

        e.message shouldBe "Not all bytes were consumed during deserialization"
    }

    "fails on a stream ending in the middle of a value, at every length" {
        for (length in encoded.indices) {
            withClue("truncated to $length bytes") {
                val e = shouldThrow<AvroMalformedDataException> { Avro.decodeFromStream<Record>(ChunkedInputStream(encoded.copyOf(length), 3)) }
                e.cause.shouldBeInstanceOf<EOFException>()
            }
        }
    }

    "fails on a stream ending in the middle of a value longer than the array" {
        val bytes = Avro.encodeToByteArray(value.copy(tag = ByteArray(2_000_000)))

        for (length in listOf(20_000, bytes.size - 1)) {
            shouldThrow<AvroMalformedDataException> { Avro.decodeFromStream<Record>(ByteArrayInputStream(bytes.copyOf(length))) }
                .cause.shouldBeInstanceOf<EOFException>()
        }
    }

    "fails on a corrupt length larger than the stream, without allocating it" {
        // A bytes field announcing nearly 2 GB, followed by 3 bytes
        val bytes = Avro.encodeToByteArray(Small(1, 2)).copyOf(1) + Avro.encodeToByteArray(Int.MAX_VALUE - 16) + byteArrayOf(1, 2, 3)

        shouldThrow<AvroMalformedDataException> { Avro.decodeFromStream<WithBytes>(ByteArrayInputStream(bytes)) }
            .cause.shouldBeInstanceOf<EOFException>()
    }

    "fails on an empty stream" {
        shouldThrow<AvroMalformedDataException> { Avro.decodeFromStream<Record>(ByteArrayInputStream(ByteArray(0))) }
            .cause.shouldBeInstanceOf<EOFException>()
    }

    "lets an IOException of the stream through unchanged" {
        val failure = IOException("connection reset")
        val stream =
            object : InputStream() {
                override fun read(): Int = throw failure
            }

        shouldThrow<IOException> { Avro.decodeFromStream<Record>(stream) } shouldBe failure
    }

    "does not close the input stream" {
        var closed = false
        val stream =
            object : ByteArrayInputStream(encoded) {
                override fun close() {
                    closed = true
                }
            }

        Avro.decodeFromStream<Record>(stream) shouldBe value
        closed shouldBe false
    }

    "writes and reads single objects through streams, the header split across refills" {
        val schema = Avro.schema<Record>()
        val singleObject = AvroSingleObject({ schema })
        val output = ByteArrayOutputStream()

        singleObject.encodeToStream(schema, Record.serializer(), value, output)

        output.toByteArray() shouldBe singleObject.encodeToByteArray(value)
        for (chunk in listOf(1, 3, 8192)) {
            singleObject.decodeFromStream(Record.serializer(), ChunkedInputStream(output.toByteArray(), chunk)) shouldBe value
        }
    }

    "fails on a single object stream ending in its header, or holding more bytes after the value" {
        val schema = Avro.schema<Record>()
        val singleObject = AvroSingleObject({ schema })
        val bytes = singleObject.encodeToByteArray(value)

        shouldThrow<AvroMalformedDataException> { singleObject.decodeFromStream(Record.serializer(), ByteArrayInputStream(bytes.copyOf(5))) }
            .cause.shouldBeInstanceOf<EOFException>()
        shouldThrow<AvroMalformedDataException> { singleObject.decodeFromStream(Record.serializer(), ByteArrayInputStream(bytes + byteArrayOf(0))) }
            .message shouldBe "Not all bytes were consumed during deserialization"
    }

    "writes the golden fixtures' bytes, and reads them back one byte per read" {
        val failures =
            allGoldenFixtures.mapNotNull { fixture ->
                runCatching { checkStreams(fixture) }.exceptionOrNull()?.let { "$fixture: $it" }
            } +
                resolutionFixtures.mapNotNull { fixture ->
                    runCatching { checkStreams(fixture) }.exceptionOrNull()?.let { "$fixture: $it" }
                }
        failures shouldBe emptyList()
    }
}) {
    @Serializable
    private data class Small(val a: Int, val b: Int)

    @Serializable
    private data class WithBytes(val a: Int, val bytes: ByteArray)

    @Serializable
    private data class Record(val id: Int, val name: String, val tag: ByteArray, val score: Double, val amounts: List<Long>) {
        override fun equals(other: Any?): Boolean =
            other is Record && id == other.id && name == other.name && tag.contentEquals(other.tag) && score == other.score && amounts == other.amounts

        override fun hashCode(): Int = id
    }

    @Serializable
    @SerialName("Extra")
    private class WithExtra(val id: Int, val extra: String, val extras: List<String>, val bytes: ByteArray, val last: Int)

    @Serializable
    @SerialName("Extra")
    private data class WithoutExtra(val id: Int, val last: Int)

    /** Never skips, so that skipping falls back to reading. */
    private class SkipRefusingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        override fun skip(n: Long): Long = 0
    }

    private class RecordingOutputStream : ByteArrayOutputStream() {
        var flushed = false
        var closed = false

        override fun flush() {
            flushed = true
        }

        override fun close() {
            closed = true
        }
    }
}

private fun <T> checkStreams(fixture: GoldenFixture<T>) {
    val avro = fixture.avro
    val schema = AvroSchema.fromJsonString(fixture.schema)
    val output = ByteArrayOutputStream()
    avro.encodeToStream(schema, fixture.serializer, fixture.value, output)
    output.toByteArray().toHexString() shouldBe fixture.hex

    val decoded = avro.decodeFromStream(schema, fixture.serializer, ChunkedInputStream(fixture.hex.hexToByteArray(), 1))
    if (fixture.structuralEquals) {
        decoded shouldBe fixture.value
    }
    avro.encodeToByteArray(schema, fixture.serializer, decoded).toHexString() shouldBe fixture.hex
}

private fun <W, R> checkStreams(fixture: ResolutionFixture<W, R>) {
    val writerSchema = AvroSchema.fromJsonString(fixture.writerSchema)
    fixture.avro.decodeFromStream(writerSchema, fixture.readerSerializer, ChunkedInputStream(fixture.hex.hexToByteArray(), 1)) shouldBe fixture.expected
}

/**
 * Returns at most [chunk] bytes per read or skip, as a socket or a pipe may. Not a [ByteArrayInputStream] subclass: its
 * `readNBytes` reads once, relying on its own `read` returning every byte asked for.
 */
private class ChunkedInputStream(bytes: ByteArray, private val chunk: Int) : InputStream() {
    private val input = ByteArrayInputStream(bytes)

    override fun read(): Int = input.read()

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int = input.read(b, off, minOf(len, chunk))

    override fun skip(n: Long): Long = input.skip(minOf(n, chunk.toLong()))
}