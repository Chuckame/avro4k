@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.serializer.KotlinInstantSerializer
import com.github.avrokotlin.avro4k.serializer.KotlinUuidSerializer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** The middleware's lookups used to be IdentityHashMaps; they must keep mapping exactly the same instances. */
internal class SerializerLocatorMiddlewareTest : StringSpec({
    fun serialized(serializer: KSerializer<*>) = SerializerLocatorMiddleware.apply(serializer as SerializationStrategy<*>)

    fun deserialized(serializer: KSerializer<*>) = SerializerLocatorMiddleware.apply(serializer as DeserializationStrategy<*>)

    "replaces the intercepted serializers, for both encoding and decoding" {
        serialized(Uuid.serializer()) shouldBeSameInstanceAs KotlinUuidSerializer
        deserialized(Uuid.serializer()) shouldBeSameInstanceAs KotlinUuidSerializer
        serialized(Instant.serializer()) shouldBeSameInstanceAs KotlinInstantSerializer
        deserialized(Instant.serializer()) shouldBeSameInstanceAs KotlinInstantSerializer
        listOf(ByteArraySerializer(), Duration.serializer()).forEach {
            serialized(it) shouldNotBeSameInstanceAs it
            deserialized(it) shouldBeSameInstanceAs serialized(it)
        }
    }

    "leaves any other serializer untouched" {
        listOf(String.serializer(), Int.serializer(), Long.serializer()).forEach {
            serialized(it) shouldBeSameInstanceAs it
            deserialized(it) shouldBeSameInstanceAs it
        }
    }

    "replaces the intercepted descriptors by avro4k's ones, keeping their serial names" {
        SerializerLocatorMiddleware.apply(Uuid.serializer().descriptor) shouldBeSameInstanceAs KotlinUuidSerializer.descriptor
        SerializerLocatorMiddleware.apply(Instant.serializer().descriptor) shouldBeSameInstanceAs KotlinInstantSerializer.descriptor
        listOf(ByteArraySerializer().descriptor, String.serializer().descriptor, Duration.serializer().descriptor).forEach {
            val replaced = SerializerLocatorMiddleware.apply(it)
            replaced shouldNotBeSameInstanceAs it
            replaced.serialName shouldBe it.serialName
        }
    }

    "leaves any other descriptor untouched" {
        listOf(Int.serializer().descriptor, Long.serializer().descriptor, Uuid.serializer().descriptor.let { KotlinUuidSerializer.descriptor }).forEach {
            SerializerLocatorMiddleware.apply(it) shouldBeSameInstanceAs it
        }
    }
})