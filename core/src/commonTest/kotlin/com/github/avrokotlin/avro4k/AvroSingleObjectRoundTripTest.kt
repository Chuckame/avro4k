package com.github.avrokotlin.avro4k

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test

/**
 * The single-object format on every platform. The expected fingerprint is what the JVM produces, where the JVM test
 * suite checks it against Apache Avro's `SchemaNormalization`.
 */
class AvroSingleObjectRoundTripTest {
    private val schema = Avro.schema<Event>()
    private val format = AvroSingleObject({ fingerprint -> schema.takeIf { fingerprint == EXPECTED_FINGERPRINT } })

    @Test
    fun `writes the marker then the fingerprint of the schema then the binary encoding`() {
        val bytes = format.encodeToByteArray(SAMPLE)

        bytes.copyOfRange(0, 2).toHexString() shouldBe "c301"
        bytes.copyOfRange(2, 10).toHexString() shouldBe EXPECTED_FINGERPRINT_HEX
        bytes.copyOfRange(10, bytes.size).toHexString() shouldBe Avro.encodeToByteArray(schema, SAMPLE).toHexString()
    }

    @Test
    fun `reads back what it writes`() {
        format.decodeFromByteArray<Event>(format.encodeToByteArray(SAMPLE)) shouldBe SAMPLE
    }

    @Test
    fun `an unknown fingerprint is reported with its value`() {
        val bytes = format.encodeToByteArray(SAMPLE)
        val unknownSchemas = AvroSingleObject({ null })

        shouldThrow<AvroSerializationException> {
            unknownSchemas.decodeFromByteArray<Event>(bytes)
        }.message shouldContain EXPECTED_FINGERPRINT.toString(16)
    }

    @Test
    fun `a wrong marker or a truncated header is malformed data`() {
        val bytes = format.encodeToByteArray(SAMPLE)

        shouldThrow<AvroMalformedDataException> {
            format.decodeFromByteArray<Event>(byteArrayOf(0) + bytes.copyOfRange(1, bytes.size))
        }
        shouldThrow<AvroMalformedDataException> {
            format.decodeFromByteArray<Event>(byteArrayOf(bytes[0], 2) + bytes.copyOfRange(2, bytes.size))
        }
        shouldThrow<AvroMalformedDataException> {
            format.decodeFromByteArray<Event>(bytes.copyOfRange(0, 6))
        }
    }

    @Serializable
    @SerialName("singleobject.Event")
    private data class Event(val id: String, val amount: Double, val tags: List<String>)

    private companion object {
        val SAMPLE = Event("order-1", 42.5, listOf("a", "b"))
        const val EXPECTED_FINGERPRINT_HEX = "a53834a1781e3497"
        val EXPECTED_FINGERPRINT: Long = EXPECTED_FINGERPRINT_HEX.hexToByteArray().foldRight(0L) { byte, acc -> (acc shl 8) or (byte.toLong() and 0xFF) }
    }
}