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

/**
 * The middleware keeps two `when` chains, one for serializers and one for descriptors, which must list the same types.
 * This is the one place listing every intercepted type: adding a type to the middleware means adding it here, and
 * the checks below then fail until *both* chains handle it.
 */
internal class SerializerLocatorMiddlewareTest : StringSpec({
    /** Every intercepted built-in serializer, with avro4k's replacement when it is public (null when private). */
    val intercepted: List<Pair<KSerializer<*>, KSerializer<*>?>> =
        listOf(
            ByteArraySerializer() to null,
            Duration.serializer() to null,
            Uuid.serializer() to KotlinUuidSerializer,
            Instant.serializer() to KotlinInstantSerializer
        )

    fun serialized(serializer: KSerializer<*>) = SerializerLocatorMiddleware.apply(serializer as SerializationStrategy<*>)

    fun deserialized(serializer: KSerializer<*>) = SerializerLocatorMiddleware.apply(serializer as DeserializationStrategy<*>)

    "every intercepted type is replaced for encoding, decoding and schema inference, consistently" {
        intercepted.forEach { (original, expected) ->
            val replacement = serialized(original)
            replacement shouldNotBeSameInstanceAs original
            expected?.let { replacement shouldBeSameInstanceAs it }
            deserialized(original) shouldBeSameInstanceAs replacement
            // The descriptor chain must map to the very descriptor of the serializer chain's replacement.
            SerializerLocatorMiddleware.apply(original.descriptor) shouldBeSameInstanceAs (replacement as KSerializer<*>).descriptor
        }
    }

    "strings keep kotlinx's serializer, and only their descriptor is replaced" {
        serialized(String.serializer()) shouldBeSameInstanceAs String.serializer()
        deserialized(String.serializer()) shouldBeSameInstanceAs String.serializer()
        val replaced = SerializerLocatorMiddleware.apply(String.serializer().descriptor)
        replaced shouldNotBeSameInstanceAs String.serializer().descriptor
        replaced.serialName shouldBe String.serializer().descriptor.serialName
    }

    "leaves any other serializer and descriptor untouched" {
        listOf(Int.serializer(), Long.serializer(), Double.serializer()).forEach {
            serialized(it) shouldBeSameInstanceAs it
            deserialized(it) shouldBeSameInstanceAs it
            SerializerLocatorMiddleware.apply(it.descriptor) shouldBeSameInstanceAs it.descriptor
        }
        // An intercepted type's replacement is not intercepted again.
        SerializerLocatorMiddleware.apply(KotlinUuidSerializer.descriptor) shouldBeSameInstanceAs KotlinUuidSerializer.descriptor
    }
})