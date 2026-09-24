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
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

class AvroSchemaTest {
    private fun parse(json: String) = AvroSchema.fromJsonString(json)

    private fun AvroSchema.toJsonString() = toJsonElement().toString()

    // ---- JSON parsing ----

    @Test
    fun parsesPrimitivesInShortAndObjectForms() {
        parse("\"null\"") shouldBe NullSchema()
        parse("\"boolean\"") shouldBe BooleanSchema()
        parse("\"int\"") shouldBe IntSchema()
        parse("\"long\"") shouldBe LongSchema()
        parse("\"float\"") shouldBe FloatSchema()
        parse("\"double\"") shouldBe DoubleSchema()
        parse("\"bytes\"") shouldBe BytesSchema()
        parse("\"string\"") shouldBe StringSchema()
        parse("""{"type":"string","logicalType":"uuid"}""") shouldBe StringSchema(mapOf("logicalType" to JsonPrimitive("uuid")))
        (parse("""{"type":"int","logicalType":"date"}""") as IntSchema).logicalTypeName shouldBe "date"
    }

    @Test
    fun parsesCollectionsAndUnions() {
        parse("""{"type":"array","items":"int","custom":1}""") shouldBe ArraySchema(IntSchema(), mapOf("custom" to JsonPrimitive(1)))
        parse("""{"type":"map","values":["null","string"]}""") shouldBe MapSchema(UnionSchema(NullSchema(), StringSchema()))
        shouldThrow<IllegalArgumentException> { parse("""[["int"]]""") }
        shouldThrow<IllegalArgumentException> { parse("""["int","int"]""") }
        shouldThrow<IllegalArgumentException> { parse("""{"type":"unknown"}""") }
        shouldThrow<IllegalArgumentException> { parse("""{"type":"array"}""") }
    }

    @Test
    fun parsesNamedTypesWithNamespaceInheritanceAndReferences() {
        val schema =
            parse(
                """
                {"type":"record","name":"Outer","namespace":"ns","doc":"the doc","aliases":["OldOuter","other.Alias"],"custom":"prop",
                 "fields":[
                   {"name":"e","type":{"type":"enum","name":"E","symbols":["A","B"],"default":"B"},"doc":"field doc","aliases":["e2"],"default":"A"},
                   {"name":"f","type":{"type":"fixed","name":"F","namespace":"other","size":4}},
                   {"name":"noNs","type":{"type":"record","name":"NoNs","namespace":"","fields":[]}},
                   {"name":"eRef","type":"E"},
                   {"name":"fRef","type":"other.F"},
                   {"name":"noNsRef","type":"NoNs"}
                 ]}
                """.trimIndent()
            ) as RecordSchema

        schema.name shouldBe Name("ns.Outer")
        schema.doc shouldBe "the doc"
        schema.aliases shouldBe setOf(Name("ns.OldOuter"), Name("other.Alias"))
        schema.props shouldBe mapOf("custom" to JsonPrimitive("prop"))

        val enum = schema.getFieldByName("e").schema as EnumSchema
        enum.name shouldBe Name("E", "ns")
        enum.symbols shouldBe listOf("A", "B")
        enum.defaultSymbol shouldBe "B"
        schema.getFieldByName("e2") shouldBeSameInstanceAs schema.getFieldByName("e")
        schema.getFieldByName("e").defaultValue shouldBe JsonPrimitive("A")
        schema.getFieldByName("e").doc shouldBe "field doc"

        val fixed = schema.getFieldByName("f").schema as FixedSchema
        fixed.fullName shouldBe "other.F"
        fixed.size shouldBe 4

        val noNs = schema.getFieldByName("noNs").schema as RecordSchema
        noNs.name.space shouldBe null
        noNs.fullName shouldBe "NoNs"

        schema.getFieldByName("eRef").schema shouldBeSameInstanceAs enum
        schema.getFieldByName("fRef").schema shouldBeSameInstanceAs fixed
        // not found in the current namespace: falls back to the null namespace
        schema.getFieldByName("noNsRef").schema shouldBeSameInstanceAs noNs
    }

    @Test
    fun parsingRegistersNamedTypesInKnownNamedTypes() {
        val known = mutableMapOf<String, AvroSchema>()
        val fixed = AvroSchema.fromJsonString("""{"type":"fixed","name":"F","namespace":"ns","size":1}""", known)
        known["ns.F"] shouldBeSameInstanceAs fixed
        AvroSchema.fromJsonString("""{"type":"array","items":"ns.F"}""", known) shouldBe ArraySchema(fixed)
        shouldThrow<IllegalArgumentException> { parse("""{"type":"array","items":"ns.F"}""") }
    }

    @Test
    fun rejectsInvalidDefaults() {
        shouldThrow<IllegalArgumentException> {
            parse("""{"type":"record","name":"R","fields":[{"name":"a","type":"int","default":"not an int"}]}""")
        }
        shouldThrow<IllegalArgumentException> {
            // the default of a union must match its first type
            parse("""{"type":"record","name":"R","fields":[{"name":"a","type":["string","null"],"default":null}]}""")
        }
        (parse("""{"type":"record","name":"R","fields":[{"name":"a","type":["null","string"],"default":null}]}""") as RecordSchema)
            .fields.single().defaultValue shouldBe JsonNull
    }

    // ---- JSON serialization ----

    @Test
    fun jsonRoundTripsForEverySchemaKind() {
        val schemas =
            listOf(
                """"int"""",
                """{"type":"long","logicalType":"timestamp-millis"}""",
                """{"type":"array","items":{"type":"map","values":"string"}}""",
                """["null","int",{"type":"enum","name":"E","symbols":["A"]}]""",
                """{"type":"fixed","name":"F","namespace":"a.b","size":16,"logicalType":"decimal","precision":5,"scale":2,"aliases":["G"]}""",
                RECURSIVE_LIST,
                """{"type":"record","name":"Outer","namespace":"ns","fields":[{"name":"inner","type":{"type":"record","name":"Inner","namespace":"","fields":[{"name":"x","type":{"type":"enum","name":"InNs","namespace":"ns","symbols":["A"]}}]}},{"name":"ref","type":"ns.InNs"}]}"""
            )
        schemas.forEach { json ->
            val schema = parse(json)
            val reparsed = parse(schema.toJsonString())
            reparsed shouldBe schema
            reparsed.toJsonString() shouldBe schema.toJsonString()
        }
    }

    @Test
    fun namedTypesAreDefinedOnceThenReferenced() {
        val json =
            """{"type":"record","name":"R","namespace":"ns","fields":[{"name":"a","type":{"type":"fixed","name":"F","size":1}},{"name":"b","type":"F"},{"name":"c","type":{"type":"fixed","name":"G","namespace":"other","size":1}},{"name":"d","type":"other.G"}]}"""
        parse(json).toJsonString() shouldBe json
    }

    @Test
    fun recordInNullNamespaceNestedInNamespacedRecordRoundTrips() {
        val schema =
            RecordSchema(
                Name("ns.Outer"),
                listOf(
                    RecordSchema.Field(
                        "inner",
                        RecordSchema(
                            Name("Inner"),
                            listOf(RecordSchema.Field("sameNsAsOuter", FixedSchema(Name("ns.F"), 2)))
                        )
                    )
                )
            )
        parse(schema.toJsonString()) shouldBe schema
        ((parse(schema.toJsonString()) as RecordSchema).fields[0].schema as RecordSchema).fields[0].schema shouldBe FixedSchema(Name("ns.F"), 2)
    }

    // ---- equals / hashCode / toString ----

    @Test
    fun recursiveSchemasParsedTwiceAreEqualWithSameHashCode() {
        val a = parse(RECURSIVE_LIST)
        val b = parse(RECURSIVE_LIST)
        a shouldNotBeSameInstanceAs b
        a shouldBe b
        a.hashCode() shouldBe b.hashCode()
        a.toString() shouldBe b.toString()
    }

    @Test
    fun mutuallyRecursiveSchemasAreEqual() {
        val json =
            """{"type":"record","name":"A","fields":[{"name":"b","type":["null",{"type":"record","name":"B","fields":[{"name":"a","type":["null","A"]},{"name":"as","type":{"type":"array","items":"A"}}]}]},{"name":"bs","type":{"type":"map","values":"B"}}]}"""
        parse(json) shouldBe parse(json)
        parse(json).hashCode() shouldBe parse(json).hashCode()
    }

    @Test
    fun recursiveSchemasWithDifferentDeepFieldsAreNotEqual() {
        val other = RECURSIVE_LIST.replace("\"int\"", "\"long\"")
        parse(RECURSIVE_LIST) shouldNotBe parse(other)
        val otherNext = RECURSIVE_LIST.replace("\"next\"", "\"nxt\"")
        parse(RECURSIVE_LIST) shouldNotBe parse(otherNext)
    }

    @Test
    fun recordEqualityIgnoresDocAndAliasesLikeApacheAvro() {
        val base = RecordSchema(Name("R"), listOf(RecordSchema.Field("a", IntSchema())))
        base.copy(doc = "doc", aliases = setOf(Name("S"))) shouldBe base
        base.copy(props = mapOf("p" to JsonPrimitive(1))) shouldNotBe base
        base.copy(name = Name("ns.R")) shouldNotBe base
        base.copy(fields = listOf(RecordSchema.Field("a", IntSchema(), defaultValue = JsonPrimitive(1)))) shouldNotBe base
    }

    @Test
    fun recordHashCodeDoesNotReadFieldsSoItWorksWhileTheRecordIsBeingBuilt() {
        val fields = LockableList<RecordSchema.Field>()
        val record = RecordSchema(Name("ns.R"), fields)
        shouldThrow<IllegalStateException> { fields.size }
        val hashBeforeLock = record.hashCode()
        fields += RecordSchema.Field("self", UnionSchema(NullSchema(), record))
        fields.lock()
        record.hashCode() shouldBe hashBeforeLock
        record.hashCode() shouldBe Name("ns.R").hashCode()
        shouldThrow<IllegalStateException> { fields += RecordSchema.Field("other", IntSchema()) }
        record.getFieldByName("self").schema shouldBe UnionSchema(NullSchema(), record)
        // Usable in a hash-based collection
        setOf(record, parse(RECURSIVE_SELF)).size shouldBe 1
    }

    @Test
    fun toStringOfRecursiveSchemaTerminates() {
        parse(RECURSIVE_LIST).toString() shouldBe
            "RecordSchema(name=ns.Node, doc=null, aliases=[], props={}, fields=Field(name=value, defaultValue=null, doc=null, aliases=[], props={}, schema=IntSchema(props={})), " +
            "Field(name=next, defaultValue=null, doc=null, aliases=[], props={}, schema=UnionSchema(types=[NullSchema(props={}), RecordSchema(name=ns.Node)])))"
    }

    // ---- nullable ----

    @Test
    fun nullable() {
        IntSchema().isNullable shouldBe false
        NullSchema().isNullable shouldBe true
        IntSchema().nullable shouldBe UnionSchema(NullSchema(), IntSchema())
        UnionSchema(IntSchema(), StringSchema()).nullable shouldBe UnionSchema(NullSchema(), IntSchema(), StringSchema())
        val alreadyNullable = UnionSchema(IntSchema(), NullSchema())
        alreadyNullable.nullable shouldBeSameInstanceAs alreadyNullable
        alreadyNullable.isSimpleNullableType shouldBe true
        UnionSchema(IntSchema(), NullSchema(), StringSchema()).isSimpleNullableType shouldBe false
    }

    // ---- names ----

    @Test
    fun names() {
        Name("a.b.C").apply {
            space shouldBe "a.b"
            simpleName shouldBe "C"
            fullName shouldBe "a.b.C"
        }
        // a dotted name ignores the given namespace, like Apache Avro
        Name("a.b.C", "x").fullName shouldBe "a.b.C"
        Name("C", "x").fullName shouldBe "x.C"
        Name("C", "").space shouldBe null
        Name("C").fullName shouldBe "C"
        Name("x.C") shouldBe Name("C", "x")
        Name("x.C").hashCode() shouldBe Name("C", "x").hashCode()
    }

    @Test
    fun unionIndexesByFullNameAndAliases() {
        val union =
            UnionSchema(
                NullSchema(),
                RecordSchema(Name("ns.R"), emptyList(), aliases = setOf(Name("ns.OldR"))),
                StringSchema()
            )
        union.getIndexOrNull("null") shouldBe 0
        union.getIndexOrNull("ns.R") shouldBe 1
        union.getIndexOrNull("ns.OldR") shouldBe 1
        union.getIndexOrNull("string") shouldBe 2
        union.getIndexOrNull("int") shouldBe null
        shouldThrow<IllegalArgumentException> {
            UnionSchema(RecordSchema(Name("A"), emptyList(), aliases = setOf(Name("B"))), RecordSchema(Name("B"), emptyList()))
        }
    }

    @Test
    fun validations() {
        shouldThrow<IllegalArgumentException> { IntSchema(mapOf("type" to JsonPrimitive("x"))) }
        shouldThrow<IllegalArgumentException> { EnumSchema(Name("E"), listOf("A", "A")) }
        shouldThrow<IllegalArgumentException> { EnumSchema(Name("E"), listOf("A"), defaultSymbol = "B") }
        shouldThrow<IllegalArgumentException> { FixedSchema(Name("F"), 1, aliases = setOf(Name("F"))) }
        shouldThrow<IllegalArgumentException> {
            RecordSchema(Name("R"), listOf(RecordSchema.Field("a", IntSchema()), RecordSchema.Field("a", IntSchema())))
        }
        shouldThrow<IllegalArgumentException> {
            RecordSchema(Name("R"), listOf(RecordSchema.Field("a", IntSchema()), RecordSchema.Field("b", IntSchema(), aliases = setOf("a"))))
        }
        shouldThrow<IllegalArgumentException> { RecordSchema.Field("a", IntSchema(), aliases = setOf("a")) }
    }

    @Test
    fun typeAndNames() {
        parse(RECURSIVE_LIST).type shouldBe AvroSchema.Type.RECORD
        UnionSchema(NullSchema()).type shouldBe AvroSchema.Type.UNION
        UnionSchema(NullSchema()).fullName shouldBe "union"
        ArraySchema(IntSchema()).fullName shouldBe "array"
        Json.parseToJsonElement(IntSchema().toJsonString()) shouldBe JsonPrimitive("int")
    }

    private companion object {
        const val RECURSIVE_LIST =
            """{"type":"record","name":"Node","namespace":"ns","fields":[{"name":"value","type":"int"},{"name":"next","type":["null","Node"]}]}"""
        const val RECURSIVE_SELF =
            """{"type":"record","name":"R","namespace":"ns","fields":[{"name":"self","type":["null","R"]}]}"""
    }
}