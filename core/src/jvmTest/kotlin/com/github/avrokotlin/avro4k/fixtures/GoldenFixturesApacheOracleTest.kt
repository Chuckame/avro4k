package com.github.avrokotlin.avro4k.fixtures

import com.github.avrokotlin.avro4k.encodeToGenericData
import com.github.avrokotlin.avro4k.schema
import com.github.avrokotlin.avro4k.toApacheSchema
import io.kotest.matchers.shouldBe
import org.apache.avro.Schema
import org.apache.avro.generic.GenericDatumReader
import org.apache.avro.generic.GenericDatumWriter
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.fail

/**
 * Checks the golden fixtures, which every platform compares itself with, against Apache Avro: the pasted schemas are the
 * ones avro4k infers, the pasted bytes are what Apache's writer produces for the value and are read by Apache, each
 * alternative encoding is one Apache reads as the same datum, and Apache's own schema resolution reads each resolution fixture as expected.
 *
 * The value reaches Apache through avro4k's generic-data encoder, then Apache's `GenericDatumWriter` lays out the bytes:
 * no byte comes from avro4k's binary encoder, which is what the fixtures test.
 */
class GoldenFixturesApacheOracleTest {
    @Test
    fun `the fixtures' schemas and bytes are Apache's`() = checkAll(allGoldenFixtures) { check(it) }

    @Test
    fun `Apache resolves the resolution fixtures as expected`() = checkAll(resolutionFixtures) { check(it) }

    private fun <F> checkAll(
        fixtures: List<F>,
        check: (F) -> Unit,
    ) {
        val failures = fixtures.mapNotNull { fixture -> runCatching { check(fixture) }.exceptionOrNull()?.let { "$fixture: $it" } }
        if (failures.isNotEmpty()) fail("${failures.size} of ${fixtures.size} fixtures failed:\n" + failures.joinToString("\n"))
    }

    private fun <T> check(fixture: GoldenFixture<T>) {
        val schema = Schema.Parser().parse(fixture.schema)
        if (fixture.inferred) {
            schema shouldBe fixture.avro.schema(fixture.serializer).toApacheSchema()
        }

        apacheWrite(schema, fixture.avro.encodeToGenericData(schema, fixture.serializer, fixture.value)) shouldBe fixture.hex

        // Compared as Apache datums rather than written again: Apache reads a map into a HashMap, which reorders it
        val datum = apacheRead(schema, schema, fixture.hex)
        for (hex in fixture.alternativeHex) {
            apacheRead(schema, schema, hex) shouldBe datum
        }
    }

    private fun <W, R> check(fixture: ResolutionFixture<W, R>) {
        val writerSchema = Schema.Parser().parse(fixture.writerSchema)
        writerSchema shouldBe fixture.avro.schema(fixture.writerSerializer).toApacheSchema()
        apacheWrite(writerSchema, fixture.avro.encodeToGenericData(writerSchema, fixture.writerSerializer, fixture.writerValue)) shouldBe fixture.hex

        val readerSchema = fixture.avro.schema(fixture.readerSerializer).toApacheSchema()
        val expected = fixture.avro.encodeToGenericData(readerSchema, fixture.readerSerializer, fixture.expected)
        apacheWrite(readerSchema, apacheRead(writerSchema, readerSchema, fixture.hex)) shouldBe apacheWrite(readerSchema, expected)
    }

    private fun apacheWrite(
        schema: Schema,
        datum: Any?,
    ): String {
        val output = ByteArrayOutputStream()
        val encoder = EncoderFactory.get().binaryEncoder(output, null)
        GenericDatumWriter<Any?>(schema).write(datum, encoder)
        encoder.flush()
        return output.toByteArray().toHexString()
    }

    private fun apacheRead(
        writerSchema: Schema,
        readerSchema: Schema,
        hex: String,
    ): Any? {
        val bytes = hex.hexToByteArray()
        val decoder = DecoderFactory.get().binaryDecoder(bytes, null)
        return GenericDatumReader<Any?>(writerSchema, readerSchema).read(null, decoder).also {
            check(decoder.isEnd) { "Apache left bytes unread in $hex" }
        }
    }
}