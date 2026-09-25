@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class, kotlin.time.ExperimentalTime::class)

package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.serializer.AnySerializer
import com.github.avrokotlin.avro4k.serializer.AvroDuration
import com.github.avrokotlin.avro4k.serializer.AvroDurationSerializer
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.builtins.serializer
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * The serial names of avro4k's own serializers are written as literals, since qualified class names are not available
 * at runtime on every platform. They must stay the qualified names they were derived from, as the serial names are
 * part of the schema inference and its caches.
 */
internal class SerialNamesTest : StringSpec({
    "the serial names of avro4k's serializers are the qualified names of their types" {
        fun replacementOf(serializer: KSerializer<*>) = SerializerLocatorMiddleware.apply(serializer as SerializationStrategy<*>) as KSerializer<*>

        val serializers: List<Pair<KSerializer<*>, KClass<*>>> =
            listOf(
                replacementOf(ByteArraySerializer()) to ByteArray::class,
                replacementOf(Duration.serializer()) to Duration::class,
                replacementOf(Uuid.serializer()) to Uuid::class,
                replacementOf(Instant.serializer()) to Instant::class,
                AvroDurationSerializer to AvroDuration::class,
                AnySerializer() to Any::class
            )
        serializers.forEach { (serializer, type) ->
            withClue(type) {
                serializer.descriptor.serialName shouldBe type.qualifiedName
            }
        }
    }
})