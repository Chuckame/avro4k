package com.github.avrokotlin.avro4k.fixtures

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.fromJsonString
import com.github.avrokotlin.avro4k.schema
import com.github.avrokotlin.avro4k.serializer.AnySerializer
import com.github.avrokotlin.avro4k.toJsonElement
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.fail

/**
 * Every platform infers the same schemas, writes the same bytes and reads the same values as the JVM, where the
 * fixtures are checked against Apache Avro. See [GoldenFixture].
 */
class GoldenFixturesTest {
    @Test
    fun `primitives at their edges are the same on every platform`() = checkAll(primitiveFixtures) { check(it) }

    @Test
    fun `records are the same on every platform`() = checkAll(recordFixtures) { check(it) }

    @Test
    fun `unions are the same on every platform`() = checkAll(unionFixtures) { check(it) }

    @Test
    fun `collections and their blocks are the same on every platform`() = checkAll(collectionFixtures) { check(it) }

    @Test
    fun `enums and fixed types are the same on every platform`() = checkAll(namedTypeFixtures) { check(it) }

    @Test
    fun `logical types are the same on every platform`() = checkAll(logicalTypeFixtures) { check(it) }

    @Test
    fun `schema resolution is the same on every platform`() = checkAll(resolutionFixtures) { check(it) }

    @Test
    fun `every fixture decodes as Any to the same values`() =
        checkAll(allGoldenFixtures) { fixture ->
            val schema = AvroSchema.fromJsonString(fixture.schema)
            renderGeneric(fixture.avro.decodeFromByteArray(schema, AnySerializer().nullable, fixture.hex.hexToByteArray()), schema) shouldBe fixture.generic
        }

    /** Checks every fixture, then fails with all the failures, so that one run on a slow platform shows them all. */
    private fun <F> checkAll(
        fixtures: List<F>,
        check: (F) -> Unit,
    ) {
        val failures = fixtures.mapNotNull { fixture -> runCatching { check(fixture) }.exceptionOrNull()?.let { "$fixture: $it" } }
        if (failures.isNotEmpty()) fail("${failures.size} of ${fixtures.size} fixtures failed:\n" + failures.joinToString("\n"))
    }

    private fun <T> check(fixture: GoldenFixture<T>) {
        val avro = fixture.avro
        if (fixture.inferred) {
            avro.schema(fixture.serializer).toJsonElement().toString() shouldBe fixture.schema
        }
        val schema = AvroSchema.fromJsonString(fixture.schema)

        avro.encodeToByteArray(schema, fixture.serializer, fixture.value).toHexString() shouldBe fixture.hex

        for (hex in listOf(fixture.hex) + fixture.alternativeHex) {
            val decoded = avro.decodeFromByteArray(schema, fixture.serializer, hex.hexToByteArray())
            if (fixture.structuralEquals) {
                decoded shouldBe fixture.value
            }
            avro.encodeToByteArray(schema, fixture.serializer, decoded).toHexString() shouldBe fixture.hex
        }
    }

    private fun <W, R> check(fixture: ResolutionFixture<W, R>) {
        val avro = fixture.avro
        avro.schema(fixture.writerSerializer).toJsonElement().toString() shouldBe fixture.writerSchema
        val writerSchema = AvroSchema.fromJsonString(fixture.writerSchema)

        avro.encodeToByteArray(writerSchema, fixture.writerSerializer, fixture.writerValue).toHexString() shouldBe fixture.hex
        avro.decodeFromByteArray(writerSchema, fixture.readerSerializer, fixture.hex.hexToByteArray()) shouldBe fixture.expected
    }
}