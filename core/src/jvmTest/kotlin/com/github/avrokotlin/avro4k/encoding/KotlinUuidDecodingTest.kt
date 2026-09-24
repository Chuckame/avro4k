@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.apacheSchema
import com.github.avrokotlin.avro4k.decodeWith
import com.github.avrokotlin.avro4k.encodeToBytesUsingApacheLib
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import org.apache.avro.Schema
import org.apache.avro.generic.GenericData
import kotlin.uuid.Uuid

internal class KotlinUuidDecodingTest : StringSpec({
    val uuid = Uuid.parse("123e4567-e89b-12d3-a456-426614174000")
    val uuidString = "123e4567-e89b-12d3-a456-426614174000"

    "decode kotlin.uuid.Uuid from string" {
        val schema = Schema.create(Schema.Type.STRING)
        val bytes = encodeToBytesUsingApacheLib(schema, uuidString)

        val decoded = Avro.decodeWith<Uuid>(schema, bytes)

        decoded shouldBe uuid
    }

    "decode nullable kotlin.uuid.Uuid from string" {
        val schema = Avro.apacheSchema<Uuid?>()

        // With value
        val bytesWithValue = encodeToBytesUsingApacheLib(schema, uuidString)
        val decodedWithValue = Avro.decodeWith<Uuid?>(schema, bytesWithValue)
        decodedWithValue shouldBe uuid

        // With null
        val bytesWithNull = encodeToBytesUsingApacheLib(schema, null)
        val decodedWithNull = Avro.decodeWith<Uuid?>(schema, bytesWithNull)
        decodedWithNull shouldBe null
    }

    "decode kotlin.uuid.Uuid from fixed" {
        val schema = Schema.createFixed("uuid", null, null, 16)
        val bytes = encodeToBytesUsingApacheLib(schema, GenericData.Fixed(schema, uuid.toByteArray()))

        val decoded = Avro.decodeWith<KotlinUuidFixed>(schema, bytes)

        decoded shouldBe KotlinUuidFixed(uuid)
    }

    "decode kotlin.uuid.Uuid from array" {
        val uuid2 = Uuid.parse("123e4567-e89b-12d3-a456-426614174001")
        val schema = Avro.apacheSchema<List<Uuid>>()
        val bytes = encodeToBytesUsingApacheLib(schema, listOf(uuidString, uuid2.toString()))

        val decoded = Avro.decodeWith<List<Uuid>>(schema, bytes)

        decoded shouldBe listOf(uuid, uuid2)
    }

    "decode kotlin.uuid.Uuid from record" {
        val uuid2 = Uuid.parse("123e4567-e89b-12d3-a456-426614174001")
        val uuid2Bytes = uuid2.toByteArray()

        val schema = Avro.apacheSchema<KotlinUuidRecord>()
        val fixedSchema = schema.fields[1].schema()
        val bytes =
            encodeToBytesUsingApacheLib(
                schema,
                GenericData.Record(schema).apply {
                    put(0, uuidString)
                    put(1, GenericData.Fixed(fixedSchema, uuid2Bytes))
                }
            )

        val decoded = Avro.decodeWith<KotlinUuidRecord>(schema, bytes)

        decoded shouldBe KotlinUuidRecord(uuid, uuid2)
    }
}) {
    @JvmInline
    @Serializable
    private value class KotlinUuidFixed(
        @AvroFixed(16) val uuid: Uuid,
    )

    @Serializable
    private data class KotlinUuidRecord(
        val uuidString: Uuid,
        @AvroFixed(16) val uuidFixed: Uuid,
    )
}