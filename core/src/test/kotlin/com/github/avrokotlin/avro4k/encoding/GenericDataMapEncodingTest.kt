package com.github.avrokotlin.avro4k.encoding

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.decodeFromGenericData
import com.github.avrokotlin.avro4k.encodeToGenericData
import com.github.avrokotlin.avro4k.schema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * Regression tests for the generic (tree) map encoder and decoder, which no longer build an
 * intermediate `Pair` per entry. Entry ordering, null values and the null-key failure must be
 * unchanged.
 */
@Suppress("DEPRECATION")
internal class GenericDataMapEncodingTest : StringSpec({
    "encodes map entries into generic data preserving the insertion order" {
        val value = linkedMapOf("z" to 1, "a" to 2, "m" to 3)
        val serializer = MapSerializer(String.serializer(), Int.serializer())

        val encoded = Avro.encodeToGenericData(Avro.schema(serializer), serializer, value)

        encoded as Map<*, *>
        encoded.keys.map { it.toString() } shouldContainExactly listOf("z", "a", "m")
        encoded.values.toList() shouldContainExactly listOf(1, 2, 3)
    }

    "round-trips a map of nullable values through generic data" {
        val value = linkedMapOf("first" to "a", "nulled" to null, "last" to "z")
        val serializer = MapSerializer(String.serializer(), String.serializer().nullable)
        val schema = Avro.schema(serializer)

        val encoded = Avro.encodeToGenericData(schema, serializer, value)
        encoded as Map<*, *>
        encoded.keys.map { it.toString() } shouldContainExactly listOf("first", "nulled", "last")
        encoded["nulled"] shouldBe null

        Avro.decodeFromGenericData(schema, serializer, encoded) shouldBe value
    }

    "round-trips an empty map through generic data" {
        val serializer = MapSerializer(String.serializer(), Int.serializer())
        val schema = Avro.schema(serializer)

        val encoded = Avro.encodeToGenericData(schema, serializer, emptyMap())
        encoded shouldBe emptyMap<String, Int>()

        Avro.decodeFromGenericData(schema, serializer, encoded) shouldBe emptyMap()
    }

    "round-trips a map nested in a record through generic data" {
        val value = RecordWithMap("before", linkedMapOf("k1" to 1, "k2" to 2), "after")
        val schema = Avro.schema(RecordWithMap.serializer())

        val encoded = Avro.encodeToGenericData(schema, RecordWithMap.serializer(), value)

        Avro.decodeFromGenericData(schema, RecordWithMap.serializer(), encoded) shouldBe value
    }

    "fails when a map key is null" {
        val serializer = MapSerializer(String.serializer().nullable, Int.serializer())
        val schema = Avro.schema(MapSerializer(String.serializer(), Int.serializer()))

        // the key position always reports the non-nullable string schema, so the generic encoder's own
        // "Map key cannot be null" guard is defensive: AbstractAvroEncoder.encodeNull rejects it first.
        shouldThrow<SerializationException> {
            Avro.encodeToGenericData(schema, serializer, mapOf(null to 1))
        }.message shouldBe "Cannot encode null value for non-null schema: \"string\""
    }
}) {
    @Serializable
    private data class RecordWithMap(
        val before: String,
        val map: Map<String, Int>,
        val after: String,
    )
}