package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.serializer.AnySerializer
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.test.Test
import kotlin.uuid.Uuid

/**
 * A `uuid` decoded as `Any` is a `kotlin.uuid.Uuid` on every platform, and encodes back to the same bytes; an invalid one is
 * ignored, and a custom logical type uses the serializer registered for it.
 */
class GenericUuidTest {
    private val uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")

    @Test
    fun `a string uuid decodes as Any to a kotlin Uuid`() {
        val schema = AvroSchema.fromJsonString("""{"type":"string","logicalType":"uuid"}""")
        val bytes = Avro.encodeToByteArray(schema, uuid.toString())

        Avro.decodeFromByteArray(schema, AnySerializer(), bytes) shouldBe uuid
    }

    @Test
    fun `a fixed uuid decodes as Any to a kotlin Uuid`() {
        val schema = AvroSchema.fromJsonString("""{"type":"fixed","name":"TheUuid","size":16,"logicalType":"uuid"}""")
        val bytes = Avro.encodeToByteArray(schema, uuid.toByteArray())

        Avro.decodeFromByteArray(schema, AnySerializer(), bytes) shouldBe uuid
    }

    @Test
    fun `an invalid uuid logical type is ignored when decoding as Any`() {
        // a uuid fixed must have 16 bytes: the specification says to ignore the logical type, so it is a plain fixed
        val schema = AvroSchema.fromJsonString("""{"type":"fixed","name":"NotAUuid","size":8,"logicalType":"uuid"}""")
        val value = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val bytes = Avro.encodeToByteArray(schema, value)

        (Avro.decodeFromByteArray(schema, AnySerializer(), bytes) as ByteArray).toList() shouldBe value.toList()
    }

    @Test
    fun `a custom logical type decodes as Any through its registered serializer`() {
        val avro = Avro { setLogicalTypeSerializer("custom-name", UpperCaseSerializer) }
        val schema = AvroSchema.fromJsonString("""{"type":"string","logicalType":"custom-name"}""")
        val bytes = avro.encodeToByteArray(schema, "value")

        avro.decodeFromByteArray(schema, AnySerializer(), bytes) shouldBe "VALUE"
    }

    private object UpperCaseSerializer : KSerializer<String> {
        override val descriptor = PrimitiveSerialDescriptor("UpperCase", PrimitiveKind.STRING)

        override fun serialize(
            encoder: Encoder,
            value: String,
        ) = encoder.encodeString(value)

        override fun deserialize(decoder: Decoder): String = decoder.decodeString().uppercase()
    }

    @Test
    fun `a kotlin Uuid encodes as Any to the uuid string`() {
        val schema = AvroSchema.fromJsonString("""{"type":"string","logicalType":"uuid"}""")

        Avro.encodeToByteArray<Any>(schema, AnySerializer(), uuid).toHexString() shouldBe
            Avro.encodeToByteArray(schema, uuid.toString()).toHexString()
    }
}