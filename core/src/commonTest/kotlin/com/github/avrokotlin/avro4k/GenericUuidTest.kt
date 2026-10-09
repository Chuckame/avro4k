package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.serializer.AnySerializer
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.uuid.Uuid

/** A `uuid` decoded as `Any` is a `kotlin.uuid.Uuid` on every platform, and encodes back to the same bytes. */
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
    fun `a kotlin Uuid encodes as Any to the uuid string`() {
        val schema = AvroSchema.fromJsonString("""{"type":"string","logicalType":"uuid"}""")

        Avro.encodeToByteArray<Any>(schema, AnySerializer(), uuid).toHexString() shouldBe
            Avro.encodeToByteArray(schema, uuid.toString()).toHexString()
    }
}