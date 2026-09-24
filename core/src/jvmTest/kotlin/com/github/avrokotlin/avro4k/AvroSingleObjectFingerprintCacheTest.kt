package com.github.avrokotlin.avro4k

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.serialization.Serializable
import org.apache.avro.Schema
import org.apache.avro.SchemaNormalization

/**
 * Regression tests for the memoization of the CRC-64-AVRO fingerprint in [AvroSingleObject].
 *
 * A one-entry or miskeyed cache still passes a test that only encodes a single schema, so every
 * assertion here either interleaves two different schemas or re-encodes after the other schema has
 * been seen.
 */
internal class AvroSingleObjectFingerprintCacheTest : StringSpec({
    val schemaA = Avro.apacheSchema(RecordA.serializer())
    val schemaB = Avro.apacheSchema(RecordB.serializer())
    val expectedFingerprintA = SchemaNormalization.parsingFingerprint("CRC-64-AVRO", schemaA)
    val expectedFingerprintB = SchemaNormalization.parsingFingerprint("CRC-64-AVRO", schemaB)

    val registry =
        mapOf(
            SchemaNormalization.parsingFingerprint64(schemaA) to schemaA,
            SchemaNormalization.parsingFingerprint64(schemaB) to schemaB
        )

    fun newFormat() = AvroSingleObject(registry::get)

    "encoding the same value twice with the same schema produces identical bytes" {
        val format = newFormat()
        val first = format.encodeWith(schemaA, RecordA.serializer(), RecordA("hello"))
        val second = format.encodeWith(schemaA, RecordA.serializer(), RecordA("hello"))

        second shouldBe first
        first.fingerprint() shouldBe expectedFingerprintA
        second.fingerprint() shouldBe expectedFingerprintA
    }

    "two different schemas produce two different fingerprints" {
        expectedFingerprintA shouldNotBe expectedFingerprintB

        val format = newFormat()
        val encodedA = format.encodeWith(schemaA, RecordA.serializer(), RecordA("hello"))
        val encodedB = format.encodeWith(schemaB, RecordB.serializer(), RecordB(42))

        encodedA.fingerprint() shouldBe expectedFingerprintA
        encodedB.fingerprint() shouldBe expectedFingerprintB
        encodedA.fingerprint() shouldNotBe encodedB.fingerprint()
    }

    "interleaving schemas on the same format instance keeps each fingerprint correct" {
        val format = newFormat()
        repeat(3) {
            format.encodeWith(schemaA, RecordA.serializer(), RecordA("hello")).fingerprint() shouldBe expectedFingerprintA
            format.encodeWith(schemaB, RecordB.serializer(), RecordB(42)).fingerprint() shouldBe expectedFingerprintB
        }
    }

    "a memoized fingerprint is still decodable by the schema registry lookup" {
        val format = newFormat()
        // warm the cache up with the other schema first, so a miskeyed cache writes the wrong fingerprint
        format.encodeWith(schemaB, RecordB.serializer(), RecordB(42))

        val bytes = format.encodeWith(schemaA, RecordA.serializer(), RecordA("hello"))
        format.decodeFromByteArray(RecordA.serializer(), bytes) shouldBe RecordA("hello")
    }

    "a distinct but structurally equal schema instance gets the same fingerprint" {
        val format = newFormat()
        val sameSchemaOtherInstance = Schema.Parser().parse(schemaA.toString())
        sameSchemaOtherInstance shouldNotBeSameInstanceAs schemaA

        format.encodeWith(sameSchemaOtherInstance, RecordA.serializer(), RecordA("hello")).fingerprint() shouldBe expectedFingerprintA
    }
}) {
    @Serializable
    private data class RecordA(val text: String)

    @Serializable
    private data class RecordB(val number: Int)
}

private fun ByteArray.fingerprint(): ByteArray = copyOfRange(2, 10)