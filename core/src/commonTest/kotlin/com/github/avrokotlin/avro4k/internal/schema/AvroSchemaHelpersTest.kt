package com.github.avrokotlin.avro4k.internal.schema

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
import com.github.avrokotlin.avro4k.AvroSchema.StringSchema
import com.github.avrokotlin.avro4k.AvroSchema.Type
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.ResolvedSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test

class AvroSchemaHelpersTest {
    // The model's own types, to build unions...
    private val recordModel =
        RecordSchema(
            Name("com.example.Rec"),
            listOf(RecordSchema.Field("a", IntSchema()), RecordSchema.Field("b", StringSchema())),
            aliases = setOf(Name("OldRec", "com.old"), Name("other.Rec2"))
        )
    private val enumModel = EnumSchema(Name("com.example.Suit"), listOf("SPADES", "HEARTS"), aliases = setOf(Name("Colour", "com.example")))
    private val fixedModel = FixedSchema(Name("com.example.Md5"), 16)
    private val arrayModel = ArraySchema(LongSchema())
    private val mapModel = MapSchema(DoubleSchema())

    // ...and the same schemas typed as `AvroSchema`, as the runtime holds them, so that the helpers (extensions on
    // `AvroSchema`) are called rather than the members of the same name (`types`, `fields`, `aliases`)
    private val record: AvroSchema = recordModel
    private val enum: AvroSchema = enumModel
    private val fixed: AvroSchema = fixedModel
    private val array: AvroSchema = arrayModel
    private val map: AvroSchema = mapModel
    private val union: AvroSchema = UnionSchema(NullSchema(), IntSchema(), StringSchema(), recordModel)

    /** One schema of each of the 14 kinds. */
    private val everyKind: List<AvroSchema> =
        listOf(
            record,
            enum,
            fixed,
            array,
            map,
            union,
            NullSchema(),
            BooleanSchema(),
            IntSchema(),
            LongSchema(),
            FloatSchema(),
            DoubleSchema(),
            BytesSchema(),
            StringSchema()
        )

    private fun logical(
        schema: ResolvedSchema,
        name: String,
    ): ResolvedSchema {
        val props = mapOf("logicalType" to JsonPrimitive(name))
        return when (schema) {
            is IntSchema -> IntSchema(props)
            is LongSchema -> LongSchema(props)
            is StringSchema -> StringSchema(props)
            is BytesSchema -> BytesSchema(props)
            is FixedSchema -> schema.copy(props = props)
            else -> error("unsupported in this test: $schema")
        }
    }

    // ---- casts ----

    @Test
    fun `asType returns the schema itself on the right kind`() {
        union.asType<UnionSchema>() shouldBeSameInstanceAs union
        record.asType<AvroSchema>() shouldBeSameInstanceAs record
    }

    @Test
    fun `asType fails on a wrong kind and names the expected class and the actual schema`() {
        shouldThrow<IllegalStateException> { array.asType<MapSchema>() }.message shouldBe
            "Expected a MapSchema, but was {\"type\":\"array\",\"items\":\"long\"}"
        for (schema in everyKind) {
            if (schema === union) continue
            shouldThrow<IllegalStateException> { schema.getIndexTyped(Type.INT) }
            shouldThrow<IllegalStateException> { schema.getIndexNamedOrAliased("x") }
        }
    }

    @Test
    fun `isUnion is only true for a union`() {
        everyKind.filter { it.isUnion } shouldBe listOf(union)
    }

    @Test
    fun `type names are the lower-case enum names`() {
        Type.entries.associateWith { it.typeName } shouldBe Type.entries.associateWith { it.name.lowercase() }
        // and the full name of every unnamed schema is its type name
        for (schema in everyKind) {
            if (!schema.isNamedSchema()) schema.fullName shouldBe schema.type.typeName
        }
    }

    // ---- names and aliases ----

    @Test
    fun `isNamedSchema is true for records - enums and fixed only`() {
        everyKind.filter { it.isNamedSchema() } shouldBe listOf(record, enum, fixed)
    }

    @Test
    fun `nonFailingAliases never throws`() {
        record.nonFailingAliases() shouldBe setOf("com.old.OldRec", "other.Rec2")
        fixed.nonFailingAliases() shouldBe emptySet()
        for (schema in everyKind) {
            if (!schema.isNamedSchema()) schema.nonFailingAliases() shouldBe emptySet()
        }
    }

    @Test
    fun `isFullNameMatch matches the full name and the aliases' full names`() {
        record.isFullNameMatch("com.example.Rec") shouldBe true
        record.isFullNameMatch("com.old.OldRec") shouldBe true
        record.isFullNameMatch("other.Rec2") shouldBe true
        record.isFullNameMatch("Rec") shouldBe false
        record.isFullNameMatch("OldRec") shouldBe false
        IntSchema().isFullNameMatch("int") shouldBe true
        IntSchema().isFullNameMatch("long") shouldBe false
        union.isFullNameMatch("union") shouldBe true
        array.isFullNameMatch("array") shouldBe true
    }

    @Test
    fun `isFullNameOrAliasMatch tries the given aliases only on a full-name miss`() {
        var calls = 0
        record.isFullNameOrAliasMatch("com.example.Rec") {
            calls++
            setOf("x")
        } shouldBe true
        calls shouldBe 0
        record.isFullNameOrAliasMatch("nope") {
            calls++
            setOf("x", "com.old.OldRec")
        } shouldBe true
        calls shouldBe 1
        record.isFullNameOrAliasMatch("nope") { setOf("x", "y") } shouldBe false
        enum.isFullNameOrAliasMatch("nope", ::emptySet) shouldBe false
        enum.isFullNameOrAliasMatch("com.example.Colour", ::emptySet) shouldBe true
    }

    // ---- union branch lookups ----

    @Test
    fun `getIndexTyped finds the branch of an unnamed type - -1 otherwise`() {
        val u = UnionSchema(NullSchema(), BooleanSchema(), IntSchema(), LongSchema(), FloatSchema(), DoubleSchema(), BytesSchema(), StringSchema(), arrayModel, mapModel)
        val unnamed = listOf(Type.NULL, Type.BOOLEAN, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE, Type.BYTES, Type.STRING, Type.ARRAY, Type.MAP)
        unnamed.forEachIndexed { index, type -> u.getIndexTyped(type) shouldBe index }
        UnionSchema(IntSchema()).getIndexTyped(Type.STRING) shouldBe -1
        u.getIndexTyped(Type.RECORD) shouldBe -1
    }

    @Test
    fun `getIndexTyped returns the first branch of a named type`() {
        val otherRecord = RecordSchema(Name("com.example.Other"), emptyList())
        UnionSchema(NullSchema(), recordModel, otherRecord).getIndexTyped(Type.RECORD) shouldBe 1
        UnionSchema(fixedModel, enumModel).getIndexTyped(Type.ENUM) shouldBe 1
    }

    @Test
    fun `getIndexLogicallyTyped matches the type and the logical type name`() {
        val date = logical(IntSchema(), "date")
        val uuid = logical(StringSchema(), "uuid")
        val durationFixed = logical(FixedSchema(Name("Duration"), 12), "duration")
        val plainFixed = FixedSchema(Name("Plain"), 12)
        val u = UnionSchema(NullSchema(), plainFixed, date, uuid, durationFixed)

        u.getIndexLogicallyTyped("date", Type.INT) shouldBe 2
        u.getIndexLogicallyTyped("uuid", Type.STRING) shouldBe 3
        u.getIndexLogicallyTyped("duration", Type.FIXED) shouldBe 4
        u.getIndexLogicallyTyped("date", Type.LONG) shouldBe -1
        u.getIndexLogicallyTyped("time-millis", Type.INT) shouldBe -1
        UnionSchema(NullSchema(), IntSchema()).getIndexLogicallyTyped("date", Type.INT) shouldBe -1
    }

    @Test
    fun `getIndexLogicallyTyped with two types gives priority to the first type - whatever the branch order`() {
        val decimalFixed = logical(FixedSchema(Name("Dec"), 8), "decimal")
        val decimalBytes = logical(BytesSchema(), "decimal")
        val u = UnionSchema(decimalFixed, decimalBytes)

        u.getIndexLogicallyTyped("decimal", Type.BYTES, Type.FIXED) shouldBe 1
        u.getIndexLogicallyTyped("decimal", Type.FIXED, Type.BYTES) shouldBe 0
        UnionSchema(decimalFixed).getIndexLogicallyTyped("decimal", Type.BYTES, Type.FIXED) shouldBe 0
        UnionSchema(decimalFixed).getIndexLogicallyTyped("decimal", Type.BYTES, Type.STRING) shouldBe -1
    }

    @Test
    fun `getIndexNamedOrAliased finds a branch by full name or alias`() {
        val u = UnionSchema(NullSchema(), recordModel, enumModel)
        u.getIndexNamedOrAliased("com.example.Rec") shouldBe 1
        u.getIndexNamedOrAliased("com.old.OldRec") shouldBe 1
        u.getIndexNamedOrAliased("com.example.Colour") shouldBe 2
        u.getIndexNamedOrAliased("null") shouldBe 0
        u.getIndexNamedOrAliased("Rec").shouldBeNull()
    }

    // ---- union shaping ----

    @Test
    fun `asSchemaList returns the branches of a union - or the schema alone`() {
        union.asSchemaList() shouldBeSameInstanceAs (union as UnionSchema).types
        val list = record.asSchemaList()
        list.size shouldBe 1
        list[0] shouldBeSameInstanceAs record
    }

    @Test
    fun `moveToHeadOfUnion moves the first matching branch to the head`() {
        val moved = union.moveToHeadOfUnion { it.type === Type.STRING } as UnionSchema
        moved.types.map { it.type } shouldBe listOf(Type.STRING, Type.NULL, Type.INT, Type.RECORD)
        moved.types[0] shouldBeSameInstanceAs (union as UnionSchema).types[2]

        (union.moveToHeadOfUnion(3) as UnionSchema).types.map { it.type } shouldBe listOf(Type.RECORD, Type.NULL, Type.INT, Type.STRING)
    }

    @Test
    fun `moveToTailOfUnion moves the first matching branch to the tail`() {
        val moved = union.moveToTailOfUnion { it.type === Type.NULL } as UnionSchema
        moved.types.map { it.type } shouldBe listOf(Type.INT, Type.STRING, Type.RECORD, Type.NULL)

        (union.moveToTailOfUnion(1) as UnionSchema).types.map { it.type } shouldBe listOf(Type.NULL, Type.STRING, Type.RECORD, Type.INT)
    }

    @Test
    fun `moving is a no-op on a non-union - without a match - or past the end`() {
        record.moveToHeadOfUnion { true } shouldBeSameInstanceAs record
        record.moveToHeadOfUnion(0) shouldBeSameInstanceAs record
        record.moveToTailOfUnion { true } shouldBeSameInstanceAs record
        record.moveToTailOfUnion(0) shouldBeSameInstanceAs record
        union.moveToHeadOfUnion { it.type === Type.BYTES } shouldBeSameInstanceAs union
        union.moveToTailOfUnion { it.type === Type.BYTES } shouldBeSameInstanceAs union
        union.moveToHeadOfUnion(4) shouldBeSameInstanceAs union
        union.moveToTailOfUnion(4) shouldBeSameInstanceAs union
    }

    @Test
    fun `moving a branch already in place still builds a new union - as the Apache helper did`() {
        val head = union.moveToHeadOfUnion(0)
        head shouldNotBeSameInstanceAs union
        head shouldBe union
        val tail = union.moveToTailOfUnion(3)
        tail shouldNotBeSameInstanceAs union
        tail shouldBe union
    }

    @Test
    fun `a negative index fails - as the Apache helper did`() {
        shouldThrow<IndexOutOfBoundsException> { union.moveToHeadOfUnion(-1) }
        shouldThrow<IndexOutOfBoundsException> { union.moveToTailOfUnion(-1) }
    }

    @Test
    fun `isNonNullScalarType accepts stringifiable types and unions of them only`() {
        val scalar = setOf(Type.BOOLEAN, Type.INT, Type.LONG, Type.FLOAT, Type.DOUBLE, Type.STRING, Type.ENUM, Type.BYTES, Type.FIXED)
        for (schema in everyKind) {
            if (schema is UnionSchema) continue
            schema.isNonNullScalarType() shouldBe (schema.type in scalar)
        }
        UnionSchema(IntSchema(), StringSchema(), enumModel, fixedModel).isNonNullScalarType() shouldBe true
        UnionSchema(IntSchema(), NullSchema()).isNonNullScalarType() shouldBe false
        UnionSchema(StringSchema(), arrayModel).isNonNullScalarType() shouldBe false
        union.isNonNullScalarType() shouldBe false
    }

    @Test
    fun `knownLogicalTypeName is the name of a valid logical type of the specification - memoized per instance`() {
        val date = IntSchema(mapOf("logicalType" to JsonPrimitive("date")))
        date.knownLogicalTypeName shouldBe "date"
        date.knownLogicalTypeName shouldBe "date" // the memoized value
        LongSchema(mapOf("logicalType" to JsonPrimitive("date"))).knownLogicalTypeName.shouldBeNull() // invalid on a long
        IntSchema(mapOf("logicalType" to JsonPrimitive("char"))).knownLogicalTypeName.shouldBeNull() // not the specification's
        IntSchema().knownLogicalTypeName.shouldBeNull()
        FixedSchema(Name("f"), 16, props = mapOf("logicalType" to JsonPrimitive("uuid"))).knownLogicalTypeName shouldBe "uuid"
        FixedSchema(Name("f"), 8, props = mapOf("logicalType" to JsonPrimitive("uuid"))).knownLogicalTypeName.shouldBeNull()
        BytesSchema(mapOf("logicalType" to JsonPrimitive("decimal"), "precision" to JsonPrimitive(4))).knownLogicalTypeName shouldBe "decimal"
        BytesSchema(mapOf("logicalType" to JsonPrimitive("decimal"))).knownLogicalTypeName.shouldBeNull() // no precision
        UnionSchema(IntSchema(), StringSchema()).knownLogicalTypeName.shouldBeNull()
    }
}