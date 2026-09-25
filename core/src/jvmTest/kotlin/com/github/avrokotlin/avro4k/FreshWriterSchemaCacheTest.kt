package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.apacheConversionCacheSize
import com.github.avrokotlin.avro4k.internal.apacheConversionEqualityCacheSize
import com.github.avrokotlin.avro4k.internal.codec.KotlinxIoDecoder
import com.github.avrokotlin.avro4k.internal.decoder.direct.AvroValueDirectDecoder
import com.github.avrokotlin.avro4k.serializer.AnySerializer
import io.kotest.assertions.fail
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.io.Buffer
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.apache.avro.Schema

/**
 * A caller may hand in a writer schema that is fresh but equal on every call: parsed from a message header, or returned by
 * a registry lookup that re-parses it. Such a schema must hit the caches keyed by writer schema through equality, and must
 * never be remembered by identity: remembering each instance would insert into copy-on-write identity tables on every call,
 * each insertion copying a table that keeps growing until the garbage collector drops the dead keys.
 *
 * Every loop is bounded by [CALLS] and checks the cache sizes at each call, so a regression fails at the first call that
 * grows a cache instead of grinding through the quadratic growth of the tables.
 */
internal class FreshWriterSchemaCacheTest : StringSpec({
    "an Apache writer schema parsed per call is converted once, without growing the conversion caches" {
        val avro = Avro {}
        val json = Avro.apacheSchema<Outer>().toString()
        val value = Outer(1, "one", Inner(2L))
        // Held until the end, so it stays the canonical instance whatever the garbage collector does meanwhile
        val first = Schema.Parser().parse(json)
        val bytes = avro.encodeWith(first, Outer.serializer(), value)
        avro.decodeWith(first, Outer.serializer(), bytes) shouldBe value

        val identitySize = apacheConversionCacheSize()
        val equalitySize = apacheConversionEqualityCacheSize()
        val workflowIdentitySize = avro.recordResolver.fieldCache.identitySize
        val workflowEqualitySize = avro.recordResolver.fieldCache.equalitySize

        repeat(CALLS) { call ->
            val fresh = Schema.Parser().parse(json)
            fresh shouldNotBeSameInstanceAs first
            avro.decodeWith(fresh, Outer.serializer(), bytes) shouldBe value
            avro.encodeWith(fresh, Outer.serializer(), value) shouldBe bytes
            if (apacheConversionCacheSize() > identitySize || apacheConversionEqualityCacheSize() > equalitySize) {
                fail("call $call grew the conversion caches: ${apacheConversionCacheSize()} > $identitySize or ${apacheConversionEqualityCacheSize()} > $equalitySize")
            }
        }

        apacheConversionCacheSize() shouldBeLessThanOrEqual identitySize
        apacheConversionEqualityCacheSize() shouldBeLessThanOrEqual equalitySize
        // The fresh schemas were all converted to the canonical one: the workflows were resolved once
        avro.recordResolver.fieldCache.identitySize shouldBe workflowIdentitySize
        avro.recordResolver.fieldCache.equalitySize shouldBe workflowEqualitySize
        first.toString() shouldBe json
    }

    "an avro4k writer schema parsed per call resolves its record workflow once" {
        val avro = Avro {}
        val json = Avro.apacheSchema<Outer>().toString()
        val first = AvroSchema.fromJsonString(json)
        val workflow = avro.recordResolver.resolveFields(first, Outer.serializer().descriptor)
        val identitySize = avro.recordResolver.fieldCache.identitySize
        val equalitySize = avro.recordResolver.fieldCache.equalitySize
        identitySize shouldBe 1
        equalitySize shouldBe 1

        repeat(CALLS) { call ->
            val fresh = AvroSchema.fromJsonString(json)
            fresh shouldNotBeSameInstanceAs first
            avro.recordResolver.resolveFields(fresh, Outer.serializer().descriptor) shouldBeSameInstanceAs workflow
            if (avro.recordResolver.fieldCache.identitySize > identitySize || avro.recordResolver.fieldCache.equalitySize > equalitySize) {
                fail("call $call grew the workflow cache")
            }
        }
        avro.recordResolver.fieldCache.identitySize shouldBe identitySize
        avro.recordResolver.fieldCache.equalitySize shouldBe equalitySize
        avro.recordResolver.resolveFields(first, Outer.serializer().descriptor) shouldBeSameInstanceAs workflow
    }

    "an avro4k writer schema parsed per call resolves the strategy of AnySerializer once per schema" {
        val avro = Avro {}
        val anySerializer = AnySerializer()
        val json = """{"type":"array","items":{"type":"record","name":"Point","fields":[{"name":"x","type":"int"},{"name":"label","type":"string"}]}}"""
        val bytes = avro.encodeWith(Schema.Parser().parse(json), listOf(Point(1, "a"), Point(2, "b")))
        val expected = listOf(mapOf("x" to 1, "label" to "a"), mapOf("x" to 2, "label" to "b"))
        val first = AvroSchema.fromJsonString(json)
        avro.decodeWithAvroSchema(first, anySerializer, bytes) shouldBe expected
        val identitySize = anySerializer.decodingCache.identitySize
        val equalitySize = anySerializer.decodingCache.equalitySize

        repeat(CALLS) { call ->
            avro.decodeWithAvroSchema(AvroSchema.fromJsonString(json), anySerializer, bytes) shouldBe expected
            if (anySerializer.decodingCache.identitySize > identitySize || anySerializer.decodingCache.equalitySize > equalitySize) {
                fail("call $call grew the decoding cache of AnySerializer")
            }
        }
        anySerializer.decodingCache.identitySize shouldBe identitySize
        anySerializer.decodingCache.equalitySize shouldBe equalitySize
        avro.decodeWithAvroSchema(first, anySerializer, bytes) shouldBe expected
    }

    "two different writer schemas with the same record name do not share a workflow" {
        val avro = Avro {}
        val narrow = """{"type":"record","name":"Evolving","fields":[{"name":"id","type":"int"}]}"""
        val wide = """{"type":"record","name":"Evolving","fields":[{"name":"id","type":"int"},{"name":"extra","type":"string"}]}"""
        val narrowBytes = avro.encodeWith(Schema.Parser().parse(narrow), Evolving.serializer(), Evolving(7))
        val wideBytes = avro.encodeWith(Schema.Parser().parse(wide), Wide.serializer(), Wide(7, "skipped"))
        narrowBytes.size shouldBe 1
        wideBytes.size shouldBe 9

        repeat(3) {
            // alternately, and fresh each time: each one hits its own entry, by equality
            avro.decodeWith(Schema.Parser().parse(narrow), Evolving.serializer(), narrowBytes) shouldBe Evolving(7)
            avro.decodeWith(Schema.Parser().parse(wide), Evolving.serializer(), wideBytes) shouldBe Evolving(7)
            avro.encodeWith(Schema.Parser().parse(wide), Wide.serializer(), Wide(7, "skipped")) shouldBe wideBytes
            avro.encodeWith(Schema.Parser().parse(narrow), Evolving.serializer(), Evolving(7)) shouldBe narrowBytes
        }
        val narrowAvro4k = AvroSchema.fromJsonString(narrow)
        val wideAvro4k = AvroSchema.fromJsonString(wide)
        (narrowAvro4k == wideAvro4k) shouldBe false
        narrowAvro4k.hashCode() shouldBe wideAvro4k.hashCode() // same bucket: only equals tells them apart
        avro.recordResolver.resolveFields(narrowAvro4k, Evolving.serializer().descriptor).decoding.size shouldBe 1
        avro.recordResolver.resolveFields(wideAvro4k, Evolving.serializer().descriptor).decoding.size shouldBe 2
    }
}) {
    @Serializable
    private data class Outer(val id: Int, val name: String, val inner: Inner)

    @Serializable
    private data class Inner(val value: Long)

    @Serializable
    @SerialName("Point")
    private data class Point(val x: Int, val label: String)

    @Serializable
    @SerialName("Evolving")
    private data class Evolving(val id: Int)

    @Serializable
    @SerialName("Evolving")
    private data class Wide(val id: Int, val extra: String)

    private companion object {
        const val CALLS = 10_000

        fun <T> Avro.decodeWithAvroSchema(
            writerSchema: AvroSchema,
            deserializer: DeserializationStrategy<T>,
            bytes: ByteArray,
        ): T {
            val source = Buffer().apply { write(bytes) }
            return AvroValueDirectDecoder(writerSchema, this, KotlinxIoDecoder(source)).decodeSerializableValue(deserializer)
        }
    }
}