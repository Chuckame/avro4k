package com.github.avrokotlin.avro4k

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToByteArray
import okio.Buffer
import okio.buffer
import okio.source
import java.io.ByteArrayInputStream

/**
 * The okio entry points go through kotlinx-io. Decoding reads ahead, so it must still consume exactly the decoded
 * value's bytes from the okio source, as the unbuffered Apache decoder they used before did.
 */
@Suppress("DEPRECATION")
internal class AvroOkioExtensionsTest : StringSpec({
    val first = OkioRecord(name = "alice", payload = ByteArray(20_000) { it.toByte() })
    val second = OkioRecord(name = "bob", payload = byteArrayOf(1, 2, 3))

    "encodes to an okio sink the same bytes as encodeToByteArray" {
        val sink = Buffer()

        Avro.encodeToSink(first, sink)
        Avro.encodeToSink(second, sink)

        sink.readByteArray() shouldBe Avro.encodeToByteArray(first) + Avro.encodeToByteArray(second)
    }

    "decodes consecutive values from an okio source, consuming exactly their bytes" {
        val bytes = Avro.encodeToByteArray(first) + Avro.encodeToByteArray(second) + byteArrayOf(42)
        val source = ByteArrayInputStream(bytes).source().buffer()

        Avro.decodeFromSource<OkioRecord>(source) shouldBe first
        Avro.decodeFromSource<OkioRecord>(source) shouldBe second
        source.readByteArray().toList() shouldBe listOf<Byte>(42)
    }
}) {
    @Serializable
    private data class OkioRecord(val name: String, val payload: ByteArray) {
        override fun equals(other: Any?): Boolean = other is OkioRecord && name == other.name && payload.contentEquals(other.payload)

        override fun hashCode(): Int = name.hashCode()
    }
}