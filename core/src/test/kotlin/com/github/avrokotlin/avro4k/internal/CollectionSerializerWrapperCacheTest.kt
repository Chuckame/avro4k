package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.Avro
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Guards the one-entry inline cache that memoizes the [AvroCollectionSerializer] wrapper, see
 * [SerializerLocatorMiddleware]. The risk the cache introduces is handing back a wrapper built around a *different*
 * collection serializer, which would be a silent data corruption, so the tests below hammer the alternating and
 * concurrent cases.
 */
internal class CollectionSerializerWrapperCacheTest : StringSpec({
    "wraps a collection deserializer and reuses the very same wrapper on a repeated call" {
        // Typed as a `DeserializationStrategy` so the `apply` overload is unambiguous.
        val listSerializer: DeserializationStrategy<List<Int>> = ListSerializer(Int.serializer())

        val first = SerializerLocatorMiddleware.apply(listSerializer)
        val second = SerializerLocatorMiddleware.apply(listSerializer)

        first.shouldBeInstanceOf<AvroCollectionSerializer<*>>()
        second shouldBeSameInstanceAs first
        first.descriptor shouldBeSameInstanceAs listSerializer.descriptor
    }

    "never returns a wrapper around the wrong serializer when two collection serializers alternate" {
        val listSerializer: DeserializationStrategy<List<Int>> = ListSerializer(Int.serializer())
        val mapSerializer: DeserializationStrategy<Map<String, Int>> = MapSerializer(String.serializer(), Int.serializer())

        repeat(1_000) {
            val wrappedList = SerializerLocatorMiddleware.apply(listSerializer)
            wrappedList.descriptor shouldBeSameInstanceAs listSerializer.descriptor

            val wrappedMap = SerializerLocatorMiddleware.apply(mapSerializer)
            wrappedMap.descriptor shouldBeSameInstanceAs mapSerializer.descriptor
        }
    }

    "keeps working when every call hands over a brand new collection serializer instance" {
        // `ListSerializer(...)` allocates a fresh instance on every call: this is the case that forbids a strong-keyed
        // identity map, and the one the single-entry cache must survive without growing.
        repeat(1_000) {
            val freshSerializer: DeserializationStrategy<List<Int>> = ListSerializer(Int.serializer())
            val wrapped = SerializerLocatorMiddleware.apply(freshSerializer)

            wrapped.shouldBeInstanceOf<AvroCollectionSerializer<*>>()
            wrapped.descriptor shouldBeSameInstanceAs freshSerializer.descriptor
        }
    }

    "round trips nested collections" {
        val value =
            NestedCollections(
                matrix = listOf(listOf(1, 2, 3), emptyList(), listOf(4)),
                byName = mapOf("a" to listOf(1L, 2L), "b" to emptyList()),
                records = listOf(Leaf("x", 1), Leaf("y", 2))
            )

        Avro.decodeFromByteArray(
            NestedCollections.serializer(),
            Avro.encodeToByteArray(NestedCollections.serializer(), value)
        ) shouldBe value
    }

    "round trips a type alternating between two different collection serializers, repeatedly" {
        val value =
            Alternating(
                first = listOf(1, 2, 3),
                second = mapOf("a" to 1, "b" to 2),
                third = listOf("x", "y"),
                fourth = mapOf(1 to listOf(1L, 2L))
            )
        val bytes = Avro.encodeToByteArray(Alternating.serializer(), value)

        repeat(200) {
            Avro.decodeFromByteArray(Alternating.serializer(), bytes) shouldBe value
        }
    }

    "decodes concurrently from several threads without mixing wrappers up" {
        val nested =
            NestedCollections(
                matrix = listOf(listOf(1, 2, 3), listOf(4, 5)),
                byName = mapOf("a" to listOf(7L)),
                records = listOf(Leaf("x", 1))
            )
        val alternating =
            Alternating(
                first = listOf(9, 8),
                second = mapOf("k" to 3),
                third = listOf("s"),
                fourth = mapOf(2 to listOf(5L, 6L))
            )
        val nestedBytes = Avro.encodeToByteArray(NestedCollections.serializer(), nested)
        val alternatingBytes = Avro.encodeToByteArray(Alternating.serializer(), alternating)

        val threadCount = 8
        val pool = Executors.newFixedThreadPool(threadCount)
        try {
            val tasks =
                (0 until threadCount).map { index ->
                    Callable {
                        repeat(500) {
                            if (index % 2 == 0) {
                                Avro.decodeFromByteArray(NestedCollections.serializer(), nestedBytes) shouldBe nested
                            } else {
                                Avro.decodeFromByteArray(Alternating.serializer(), alternatingBytes) shouldBe alternating
                            }
                        }
                    }
                }
            pool.invokeAll(tasks).forEach { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(1, TimeUnit.MINUTES)
        }
    }
})

@Serializable
internal data class Leaf(val name: String, val value: Int)

@Serializable
internal data class NestedCollections(
    val matrix: List<List<Int>>,
    val byName: Map<String, List<Long>>,
    val records: List<Leaf>,
)

@Serializable
internal data class Alternating(
    val first: List<Int>,
    val second: Map<String, Int>,
    val third: List<String>,
    val fourth: Map<Int, List<Long>>,
)