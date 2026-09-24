package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.ArraySchema
import com.github.avrokotlin.avro4k.AvroSchema.BooleanSchema
import com.github.avrokotlin.avro4k.AvroSchema.BytesSchema
import com.github.avrokotlin.avro4k.AvroSchema.DoubleSchema
import com.github.avrokotlin.avro4k.AvroSchema.EnumSchema
import com.github.avrokotlin.avro4k.AvroSchema.FixedSchema
import com.github.avrokotlin.avro4k.AvroSchema.FloatSchema
import com.github.avrokotlin.avro4k.AvroSchema.IntSchema
import com.github.avrokotlin.avro4k.AvroSchema.LongSchema
import com.github.avrokotlin.avro4k.AvroSchema.MapSchema
import com.github.avrokotlin.avro4k.AvroSchema.NullSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema.Field
import com.github.avrokotlin.avro4k.AvroSchema.StringSchema
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.LockableList
import com.github.avrokotlin.avro4k.Name
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * M3-10: known vectors of the Parsing Canonical Form and the CRC-64-AVRO fingerprint, on every platform. Every expected
 * value was computed once on the JVM with Apache Avro 1.12.1's `SchemaNormalization` (`toParsingForm`,
 * `parsingFingerprint64`, `parsingFingerprint("CRC-64-AVRO", …)`, `fingerprint64`) from the equivalent JSON schema,
 * written next to each case, and pasted here as literals. The JVM differential test (`SchemaFingerprintDifferentialTest`)
 * compares against Apache live, over a much larger corpus.
 */
class SchemaFingerprintTest {
    private fun assertVector(
        schema: AvroSchema,
        canonicalForm: String,
        fingerprint: Long,
    ) {
        assertEquals(canonicalForm, schema.toParsingCanonicalForm())
        assertEquals(fingerprint, schema.parsingFingerprint64())
        assertEquals(fingerprint, crc64Avro(canonicalForm.encodeToByteArray()))
        assertContentEquals(fingerprint.toLittleEndianBytes(), schema.parsingFingerprintCrc64AvroBytes())
    }

    @Test
    fun `primitive schemas have the spec fingerprints`() {
        assertVector(NullSchema(), "\"null\"", 0x63dd24e7cc258f8aL)
        assertVector(BooleanSchema(), "\"boolean\"", 0x9f42fc78a4d4f764uL.toLong())
        assertVector(IntSchema(), "\"int\"", 0x7275d51a3f395c8fL)
        assertVector(LongSchema(), "\"long\"", 0xd054e14493f41db7uL.toLong())
        assertVector(FloatSchema(), "\"float\"", 0x4d7c02cb3ea8d790L)
        assertVector(DoubleSchema(), "\"double\"", 0x8e7535c032ab957euL.toLong())
        assertVector(BytesSchema(), "\"bytes\"", 0x4fc016dac3201965L)
        assertVector(StringSchema(), "\"string\"", 0x8f014872634503c7uL.toLong())
    }

    @Test
    fun `a primitive with a logical type and props is its bare type name`() {
        // {"type":"string","logicalType":"uuid","custom":1}
        assertVector(StringSchema(mapOf("logicalType" to JsonPrimitive("uuid"), "custom" to JsonPrimitive(1))), "\"string\"", 0x8f014872634503c7uL.toLong())
    }

    @Test
    fun `a record keeps only names and types with namespaces folded into full names`() {
        // {"type":"record","name":"Point","namespace":"geo","doc":"p","aliases":["Old"],"fields":[
        //   {"name":"x","type":"int","default":0,"order":"descending"},
        //   {"name":"label","type":["null",{"type":"string","logicalType":"uuid"}],"default":null}]}
        val point =
            RecordSchema(
                name = Name("Point", "geo"),
                doc = "p",
                aliases = setOf(Name("Old", "geo")),
                fields =
                    listOf(
                        Field("x", IntSchema(), defaultValue = JsonPrimitive(0), props = mapOf("order" to JsonPrimitive("descending"))),
                        Field("label", UnionSchema(NullSchema(), StringSchema(mapOf("logicalType" to JsonPrimitive("uuid")))), defaultValue = JsonNull)
                    )
            )
        assertVector(
            point,
            """{"name":"geo.Point","type":"record","fields":[{"name":"x","type":"int"},{"name":"label","type":["null","string"]}]}""",
            0x31ecbcd102b8a7a3L
        )
        assertContentEquals(byteArrayOf(-93, -89, -72, 2, -47, -68, -20, 49), point.parsingFingerprintCrc64AvroBytes())
    }

    @Test
    fun `an enum keeps its symbols but not its default`() {
        // {"type":"enum","name":"Suit","namespace":"cards","symbols":["SPADES","HEARTS"],"default":"HEARTS"}
        val suit = EnumSchema(Name("Suit", "cards"), listOf("SPADES", "HEARTS"), defaultSymbol = "HEARTS")
        assertVector(suit, """{"name":"cards.Suit","type":"enum","symbols":["SPADES","HEARTS"]}""", 0x292f5c362f36edadL)
        assertContentEquals(byteArrayOf(-83, -19, 54, 47, 54, 92, 47, 41), suit.parsingFingerprintCrc64AvroBytes())
    }

    @Test
    fun `a fixed keeps its size as an integer`() {
        // {"type":"fixed","name":"md5","namespace":"org.example","size":16}
        val md5 = FixedSchema(Name("md5", "org.example"), 16)
        assertVector(md5, """{"name":"org.example.md5","type":"fixed","size":16}""", 0x46310cea9edae9a0L)
        assertContentEquals(byteArrayOf(-96, -23, -38, -98, -22, 12, 49, 70), md5.parsingFingerprintCrc64AvroBytes())
    }

    @Test
    fun `arrays and maps keep their type then their items or values`() {
        // {"type":"array","items":{"type":"map","values":"long"}}
        assertVector(
            ArraySchema(MapSchema(LongSchema())),
            """{"type":"array","items":{"type":"map","values":"long"}}""",
            0xd37748dab2be7abduL.toLong()
        )
    }

    @Test
    fun `a recursive record refers to itself by its full name`() {
        // {"type":"record","name":"LinkedList","namespace":"rec","fields":[
        //   {"name":"value","type":"long"},{"name":"next","type":["null","LinkedList"]}]}
        val fields = LockableList<Field>()
        val linkedList = RecordSchema(Name("LinkedList", "rec"), fields)
        fields += Field("value", LongSchema())
        fields += Field("next", UnionSchema(NullSchema(), linkedList))
        fields.lock()
        assertVector(
            linkedList,
            """{"name":"rec.LinkedList","type":"record","fields":[{"name":"value","type":"long"},{"name":"next","type":["null","rec.LinkedList"]}]}""",
            0xa73d31955fecd984uL.toLong()
        )
        assertContentEquals(byteArrayOf(-124, -39, -20, 95, -107, 49, 61, -89), linkedList.parsingFingerprintCrc64AvroBytes())
    }

    @Test
    fun `nested namespaces - inherited or emptied or dotted names - and a named type written in full only once`() {
        // {"type":"record","name":"Outer","namespace":"a.b","fields":[
        //   {"name":"i","type":{"type":"record","name":"Inner","fields":[]}},
        //   {"name":"g","type":{"type":"record","name":"Global","namespace":"","fields":[{"name":"again","type":"a.b.Inner"}]}},
        //   {"name":"o","type":{"type":"enum","name":"x.E","symbols":["A"]}}]}
        val inner = RecordSchema(Name("Inner", "a.b"), emptyList())
        val outer =
            RecordSchema(
                Name("Outer", "a.b"),
                listOf(
                    Field("i", inner),
                    Field("g", RecordSchema(Name("Global", ""), listOf(Field("again", inner)))),
                    Field("o", EnumSchema(Name("x.E", "a.b"), listOf("A")))
                )
            )
        assertVector(
            outer,
            """{"name":"a.b.Outer","type":"record","fields":[{"name":"i","type":{"name":"a.b.Inner","type":"record","fields":[]}},""" +
                """{"name":"g","type":{"name":"Global","type":"record","fields":[{"name":"again","type":"a.b.Inner"}]}},""" +
                """{"name":"o","type":{"name":"x.E","type":"enum","symbols":["A"]}}]}""",
            0xfa7592ab685e8fc3uL.toLong()
        )
    }

    @Test
    fun `a named type is deduplicated by full name and not by instance`() {
        // two equal but distinct instances: the second one is written as its name, as Apache does
        val outer =
            RecordSchema(
                Name("Outer"),
                listOf(Field("a", FixedSchema(Name("F"), 2)), Field("b", FixedSchema(Name("F"), 2)))
            )
        assertEquals(
            """{"name":"Outer","type":"record","fields":[{"name":"a","type":{"name":"F","type":"fixed","size":2}},{"name":"b","type":"F"}]}""",
            outer.toParsingCanonicalForm()
        )
    }

    @Test
    fun `CRC-64-AVRO of strings and bytes`() {
        assertEquals(CRC64_AVRO_EMPTY, crc64Avro(""))
        assertEquals(0xc15d213aa4d7a795uL.toLong(), crc64Avro(ByteArray(0)))
        assertEquals(0x0ade663b45cf10e2L, crc64Avro("a"))
        assertEquals(0x1bef875ce2963804L, crc64Avro("abc"))
        // 2-byte and 4-byte UTF-8 sequences
        assertEquals(0x49ccf769c7c7baf0L, crc64Avro("héllo 😀"))
        assertEquals(0x49ccf769c7c7baf0L, crc64Avro("héllo 😀".encodeToByteArray()))
    }

    @Test
    fun `an unpaired surrogate is hashed as a question mark on every platform like the JVM's UTF-8 encoder`() {
        assertEquals(crc64Avro("a?b"), crc64Avro("a\uD800b"))
        assertEquals(crc64Avro("a?b"), crc64Avro("a\uDC00b"))
        assertEquals(crc64Avro("a?"), crc64Avro("a\uD83D"))
        assertEquals(crc64Avro("??"), crc64Avro("\uDE00\uD83D"))
    }

    @Test
    fun `the little-endian encoding writes the least significant byte first`() {
        assertContentEquals(byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1), 0x0102030405060708L.toLittleEndianBytes())
        assertContentEquals(byteArrayOf(-1, 0, 0, 0, 0, 0, 0, -128), (-0x7fffffffffffff01L).toLittleEndianBytes())
    }
}