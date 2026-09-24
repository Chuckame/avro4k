package com.github.avrokotlin.avro4k

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
import com.github.avrokotlin.avro4k.AvroSchema.StringSchema
import com.github.avrokotlin.avro4k.AvroSchema.Type
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

/**
 * The model's members read once per value by encoders and decoders (M3-04): stored [AvroSchema.type] and
 * [AvroSchema.logicalTypeName], and the O(1) enum symbol and record field lookups.
 */
class AvroSchemaHotPathTest {
    private fun parse(json: String) = AvroSchema.fromJsonString(json)

    // ---- type ----

    @Test
    fun typeOfEverySubtype() {
        val record = RecordSchema(Name("R"), emptyList())
        mapOf(
            NullSchema() to Type.NULL,
            BooleanSchema() to Type.BOOLEAN,
            IntSchema() to Type.INT,
            LongSchema() to Type.LONG,
            FloatSchema() to Type.FLOAT,
            DoubleSchema() to Type.DOUBLE,
            BytesSchema() to Type.BYTES,
            StringSchema() to Type.STRING,
            ArraySchema(IntSchema()) to Type.ARRAY,
            MapSchema(IntSchema()) to Type.MAP,
            UnionSchema(NullSchema(), IntSchema()) to Type.UNION,
            record to Type.RECORD,
            EnumSchema(Name("E"), listOf("A")) to Type.ENUM,
            FixedSchema(Name("F"), 4) to Type.FIXED
        ).forEach { (schema, type) -> schema.type shouldBe type }
        // every Type is covered
        Type.entries.size shouldBe 14
        // parsed schemas and copies get it too
        parse(""""long"""").type shouldBe Type.LONG
        record.copy(name = Name("S")).type shouldBe Type.RECORD
    }

    // ---- logicalTypeName ----

    @Test
    fun logicalTypeNameIsReadFromPropsOnEveryResolvedSchema() {
        val logical = mapOf("logicalType" to JsonPrimitive("my-type"))
        IntSchema(logical).logicalTypeName shouldBe "my-type"
        StringSchema(logical).logicalTypeName shouldBe "my-type"
        ArraySchema(IntSchema(), logical).logicalTypeName shouldBe "my-type"
        RecordSchema(Name("R"), emptyList(), props = logical).logicalTypeName shouldBe "my-type"
        parse("""{"type":"fixed","name":"F","size":8,"logicalType":"decimal","precision":5}""").logicalTypeName shouldBe "decimal"
        parse("""{"type":"bytes","logicalType":"decimal","precision":5}""").logicalTypeName shouldBe "decimal"
        parse("""{"type":"long","logicalType":"timestamp-millis"}""").logicalTypeName shouldBe "timestamp-millis"
    }

    @Test
    fun logicalTypeNameIsNullWhenAbsentOnUnionsAndWhenMalformed() {
        IntSchema().logicalTypeName shouldBe null
        IntSchema(mapOf("other" to JsonPrimitive("x"))).logicalTypeName shouldBe null
        RecordSchema(Name("R"), emptyList()).logicalTypeName shouldBe null
        // a union never has one, even when its branches do
        UnionSchema(NullSchema(), StringSchema(mapOf("logicalType" to JsonPrimitive("uuid")))).logicalTypeName shouldBe null
        // JSON null, or not a primitive: ignored instead of throwing
        IntSchema(mapOf("logicalType" to JsonNull)).logicalTypeName shouldBe null
        IntSchema(mapOf("logicalType" to JsonObject(emptyMap()))).logicalTypeName shouldBe null
    }

    // ---- enum ----

    @Test
    fun enumSymbolIndex() {
        val enum = EnumSchema(Name("E"), listOf("A", "B", "C"))
        enum.getSymbolIndexOrNull("A") shouldBe 0
        enum.getSymbolIndexOrNull("B") shouldBe 1
        enum.getSymbolIndexOrNull("C") shouldBe 2
        enum.getSymbolIndexOrNull("D") shouldBe null
        enum.getSymbolIndexOrNull("a") shouldBe null
        enum.getSymbolIndexOrNull("") shouldBe null
        EnumSchema(Name("E"), emptyList()).getSymbolIndexOrNull("A") shouldBe null
        // duplicates cannot exist, so an index is unambiguous
        shouldThrow<IllegalArgumentException> { EnumSchema(Name("E"), listOf("A", "B", "A")) }
        // the default symbol is checked against the index
        EnumSchema(Name("E"), listOf("A", "B"), defaultSymbol = "B").defaultSymbol shouldBe "B"
        shouldThrow<IllegalArgumentException> { EnumSchema(Name("E"), listOf("A"), defaultSymbol = "Z") }
        // copies get their own, up-to-date index
        enum.copy(symbols = listOf("C", "A")).getSymbolIndexOrNull("A") shouldBe 1
    }

    // ---- record fields ----

    @Test
    fun recordFieldLookupByNameAndAlias() {
        val a = RecordSchema.Field("a", IntSchema(), aliases = setOf("oldA", "olderA"))
        val b = RecordSchema.Field("b", StringSchema())
        val record = RecordSchema(Name("R"), listOf(a, b))

        record.getFieldIndexOrNull("a") shouldBe 0
        record.getFieldIndexOrNull("oldA") shouldBe 0
        record.getFieldIndexOrNull("olderA") shouldBe 0
        record.getFieldIndexOrNull("b") shouldBe 1
        record.getFieldIndexOrNull("c") shouldBe null

        record.getFieldOrNull("a") shouldBeSameInstanceAs a
        record.getFieldOrNull("oldA") shouldBeSameInstanceAs a
        record.getFieldOrNull("b") shouldBeSameInstanceAs b
        record.getFieldOrNull("c") shouldBe null

        record.getFieldByName("olderA") shouldBeSameInstanceAs a
        shouldThrow<NoSuchElementException> { record.getFieldByName("c") }.message shouldContain "'c'"

        RecordSchema(Name("Empty"), emptyList()).getFieldOrNull("a") shouldBe null
    }

    @Test
    fun recursiveRecordFieldLookupThrowsBeforeLockAndWorksAfter() {
        val fields = LockableList<RecordSchema.Field>()
        val record = RecordSchema(Name("ns.Node"), fields)

        shouldThrow<IllegalStateException> { record.getFieldOrNull("value") }.message shouldContain "before its LockableList of fields is locked"
        shouldThrow<IllegalStateException> { record.getFieldIndexOrNull("value") }.message shouldContain "before its LockableList of fields is locked"
        shouldThrow<IllegalStateException> { record.getFieldByName("value") }.message shouldContain "before its LockableList of fields is locked"

        fields += RecordSchema.Field("value", IntSchema(), aliases = setOf("v"))
        // still unlocked: a lookup must not cache a partial index
        shouldThrow<IllegalStateException> { record.getFieldOrNull("value") }
        fields += RecordSchema.Field("next", UnionSchema(NullSchema(), record))
        fields.lock()

        record.getFieldIndexOrNull("value") shouldBe 0
        record.getFieldIndexOrNull("v") shouldBe 0
        record.getFieldIndexOrNull("next") shouldBe 1
        record.getFieldIndexOrNull("other") shouldBe null
        (record.getFieldByName("next").schema as UnionSchema).types[1] shouldBeSameInstanceAs record
    }

    @Test
    fun recursiveRecordFromJsonIsLookedUpAfterParsing() {
        val record = parse(RECURSIVE_LIST) as RecordSchema
        record.getFieldIndexOrNull("next") shouldBe 1
        val next = (record.getFieldOrNull("next")!!.schema as UnionSchema).types[1] as RecordSchema
        next shouldBeSameInstanceAs record
        next.getFieldIndexOrNull("value") shouldBe 0
    }

    // ---- fixed ----

    @Test
    fun fixedSizeMustNotBeNegative() {
        shouldThrow<IllegalArgumentException> { FixedSchema(Name("F"), -1) }.message shouldContain "-1"
        shouldThrow<IllegalArgumentException> { FixedSchema(Name("F"), Int.MIN_VALUE) }
        shouldThrow<IllegalArgumentException> { parse("""{"type":"fixed","name":"F","size":-1}""") }
        FixedSchema(Name("F"), 0).size shouldBe 0
        FixedSchema(Name("F"), 16).size shouldBe 16
    }

    // ---- the caches take no part in equality ----

    @Test
    fun warmCachesDoNotAffectEqualsHashCodeAndToString() {
        val cold = parse(RECURSIVE_LIST) as RecordSchema
        val warm = parse(RECURSIVE_LIST) as RecordSchema
        warm shouldNotBeSameInstanceAs cold
        warm.getFieldOrNull("next")
        warm.getFieldIndexOrNull("value")
        ((warm.getFieldByName("next").schema as UnionSchema).types[1] as RecordSchema).getFieldIndexOrNull("value")

        warm shouldBe cold
        cold shouldBe warm
        warm.hashCode() shouldBe cold.hashCode()
        warm.toString() shouldBe cold.toString()
        warm.toString().contains("fieldIndex") shouldBe false
        // a copy of a warm record starts cold and is still equal
        warm.copy() shouldBe cold
        warm.copy().getFieldIndexOrNull("next") shouldBe 1

        val coldEnum = EnumSchema(Name("E"), listOf("A", "B"))
        val warmEnum = EnumSchema(Name("E"), listOf("A", "B"))
        warmEnum.getSymbolIndexOrNull("B")
        warmEnum shouldBe coldEnum
        warmEnum.hashCode() shouldBe coldEnum.hashCode()
        warmEnum.toString() shouldBe coldEnum.toString()
        warmEnum.toString() shouldBe "EnumSchema(name=E, symbols=[A, B], defaultSymbol=null, doc=null, aliases=[], props={})"

        // the stored type and logical type name are not data class members either
        val decimal = mapOf("logicalType" to JsonPrimitive("decimal"))
        BytesSchema(decimal) shouldBe BytesSchema(decimal)
        BytesSchema(decimal).toString() shouldBe "BytesSchema(props={logicalType=\"decimal\"})"
        FixedSchema(Name("F"), 4, props = decimal).toString() shouldBe
            "FixedSchema(name=F, size=4, doc=null, aliases=[], props={logicalType=\"decimal\"})"
    }

    private companion object {
        const val RECURSIVE_LIST =
            """{"type":"record","name":"Node","namespace":"ns","fields":[{"name":"value","type":"int"},{"name":"next","type":["null","Node"]}]}"""
    }
}