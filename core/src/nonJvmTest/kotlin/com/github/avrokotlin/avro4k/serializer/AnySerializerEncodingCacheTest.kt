package com.github.avrokotlin.avro4k.serializer

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.schema
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlin.test.Test

class AnySerializerEncodingCacheTest {
    @Serializable
    private data class Point(val x: Int, val y: Int)

    @Test
    fun `the encoding cache holds one entry per encoded class however many values are encoded`() {
        val serializer = AnySerializer()
        val intSchema = AvroSchema.IntSchema()
        val longSchema = AvroSchema.LongSchema()
        val stringSchema = AvroSchema.StringSchema()
        val pointSchema = Avro.schema<Point>()

        repeat(1_000) { i ->
            Avro.encodeToByteArray(intSchema, serializer, i)
            Avro.encodeToByteArray(longSchema, serializer, i.toLong())
            Avro.encodeToByteArray(stringSchema, serializer, "value $i")
            Avro.encodeToByteArray(pointSchema, serializer, Point(i, -i))

            serializer.encodingCacheSize shouldBeLessThanOrEqual 4
        }
        serializer.encodingCacheSize shouldBe 4
    }

    @Test
    fun `an encoded value decodes back as the same value`() {
        val serializer = AnySerializer()
        val schema = AvroSchema.StringSchema()

        Avro.decodeFromByteArray(schema, serializer, Avro.encodeToByteArray(schema, serializer, "héllo")) shouldBe "héllo"
    }
}