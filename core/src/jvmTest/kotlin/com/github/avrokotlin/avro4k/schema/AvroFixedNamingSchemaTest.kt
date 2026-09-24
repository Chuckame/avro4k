@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.github.avrokotlin.avro4k.schema

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroAlias
import com.github.avrokotlin.avro4k.AvroDecimal
import com.github.avrokotlin.avro4k.AvroDoc
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.apacheSchema
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import org.apache.avro.Schema
import org.apache.avro.SchemaCompatibility
import org.apache.avro.SchemaCompatibility.SchemaCompatibilityType
import java.math.BigDecimal
import java.util.UUID
import kotlin.uuid.Uuid

/**
 * How a fixed type is named:
 * - `@AvroFixed` on a value class's own property: the value class's serial name, aliases and doc;
 * - `@AvroFixed` on a data class property: `<record full name>.<property>`.
 */
internal class AvroFixedNamingSchemaTest : StringSpec({
    "a value class's fixed is named after the value class's serial name" {
        val fixed = Avro.apacheSchema<DefaultNamedHash>()
        fixed.type shouldBe Schema.Type.FIXED
        fixed.fullName shouldBe "com.github.avrokotlin.avro4k.schema.AvroFixedNamingSchemaTest.DefaultNamedHash"
        fixed.fixedSize shouldBe 4
        fixed.doc shouldBe null
        fixed.aliases shouldBe emptySet()
        fixed.shouldParseBackEqual()
    }

    "@SerialName, @AvroAlias and @AvroDoc on a value class are the fixed's name, aliases and doc" {
        val fixed = Avro.apacheSchema<CustomizedHash>()
        fixed.name shouldBe "Hash"
        fixed.namespace shouldBe "com.example"
        fixed.doc shouldBe "a hash"
        // An alias without a namespace is in the fixed's namespace, as for records
        fixed.aliases shouldBe setOf("com.example.OldHash", "other.ns.LegacyHash")
        fixed.shouldParseBackEqual()
    }

    "a value class's serial name without a dot means no namespace" {
        val fixed = Avro.apacheSchema<NoNamespaceHash>()
        fixed.fullName shouldBe "NoNamespaceHash"
        fixed.namespace shouldBe null
        fixed.aliases shouldBe setOf("OldNoNamespaceHash")

        // Nested in a record that has a namespace, it is still in no namespace
        val record = Avro.apacheSchema<RecordWithNoNamespaceHash>()
        record.getField("hash").schema().fullName shouldBe "NoNamespaceHash"
        record.shouldParseBackEqual()
    }

    "a data class property's fixed is named <record full name>.<property>" {
        val record = Avro.apacheSchema<RecordA>()
        val fixed = record.getField("id").schema()
        fixed.name shouldBe "id"
        fixed.namespace shouldBe "ns.A"
        fixed.fullName shouldBe "ns.A.id"
        fixed.doc shouldBe null
        fixed.aliases shouldBe emptySet()
        record.shouldParseBackEqual()
    }

    "a data class property's fixed with a record serial name without a dot is in the record's name as namespace" {
        val record = Avro.apacheSchema<NoNamespaceRecord>()
        record.getField("id").schema().fullName shouldBe "NoNamespaceRecord.id"
        record.shouldParseBackEqual()
    }

    "two records' fixed fields with the same name and different sizes do not clash" {
        val record = Avro.apacheSchema<TwoRecordsWithSameFixedFieldName>()
        val fixedOfA = record.getField("a").schema().getField("id").schema()
        val fixedOfB = record.getField("b").schema().getField("id").schema()
        fixedOfA.fullName shouldBe "ns.A.id"
        fixedOfA.fixedSize shouldBe 4
        fixedOfB.fullName shouldBe "ns.B.id"
        fixedOfB.fixedSize shouldBe 8
        record.shouldParseBackEqual()
    }

    "a data class property typed as a value class wins over the value class's @AvroFixed, and is named after the property" {
        val record = Avro.apacheSchema<FieldOverridesValueClass>()
        val fixed = record.getField("hash").schema()
        fixed.fullName shouldBe "ns.FieldOverridesValueClass.hash"
        fixed.fixedSize shouldBe 8
        // The value class's aliases and doc are those of its own fixed only
        fixed.aliases shouldBe emptySet()
        fixed.doc shouldBe null
        record.shouldParseBackEqual()
    }

    "a value class's fixed keeps its name when nullable, in a collection, or wrapped in another value class" {
        val record = Avro.apacheSchema<ValueClassUses>()
        record.getField("nullable").schema().types[1].fullName shouldBe "com.example.Hash"
        record.getField("list").schema().elementType.fullName shouldBe "com.example.Hash"
        record.getField("map").schema().valueType.fullName shouldBe "com.example.Hash"
        record.getField("wrapped").schema().fullName shouldBe "com.example.Hash"
        // The outermost value class carrying @AvroFixed names it
        record.getField("outer").schema().fullName shouldBe "ns.OuterFixed"
        record.getField("outer").schema().fixedSize shouldBe 3
        record.shouldParseBackEqual()
    }

    "a value class used by two records is one fixed" {
        val record = Avro.apacheSchema<TwoRecordsWithSameValueClass>()
        record.getField("a").schema().getField("hash").schema().fullName shouldBe "com.example.Hash"
        record.getField("b").schema().getField("hash").schema().fullName shouldBe "com.example.Hash"
        record.shouldParseBackEqual()
    }

    "two different value classes with the same serial name are rejected with Apache's message" {
        shouldThrow<SerializationException> { Avro.apacheSchema<ConflictingValueClasses>() }.message.let {
            it shouldStartWith "Can't redefine: ns.Conflict. "
            it shouldContain """{"type":"fixed","name":"Conflict","namespace":"ns","size":4}"""
            it shouldContain """{"type":"fixed","name":"Conflict","namespace":"ns","size":8}"""
        }
    }

    "a record meeting a fixed of the same full name is rejected with Apache's message" {
        shouldThrow<SerializationException> { Avro.apacheSchema<RecordClashingWithFixed>() }
            .message shouldStartWith "Can't redefine: ns.Clash. "
    }

    "UUID, kotlin.uuid.Uuid and BigDecimal fixed follow the same naming" {
        val record = Avro.apacheSchema<LogicalTypeFixed>()
        record.getField("uuid").schema().let {
            it.fullName shouldBe "ns.LogicalTypeFixed.uuid"
            it.logicalType.name shouldBe "uuid"
        }
        record.getField("kotlinUuid").schema().let {
            it.fullName shouldBe "ns.LogicalTypeFixed.kotlinUuid"
            it.logicalType.name shouldBe "uuid"
        }
        record.getField("decimal").schema().let {
            it.fullName shouldBe "ns.LogicalTypeFixed.decimal"
            it.logicalType.name shouldBe "decimal"
        }
        // The logical type is added by a copy of the created fixed, which keeps the value class's aliases and doc
        record.getField("uuidValueClass").schema().let {
            it.fullName shouldBe "ns.TheUuid"
            it.aliases shouldBe setOf("ns.OldUuid")
            it.doc shouldBe "a uuid"
            it.logicalType.name shouldBe "uuid"
        }
        record.getField("decimalValueClass").schema().let {
            it.fullName shouldBe "ns.TheDecimal"
            it.aliases shouldBe setOf("ns.OldDecimal")
            it.doc shouldBe "a decimal"
            it.logicalType.name shouldBe "decimal"
            it.getObjectProp("precision") shouldBe 5
        }
        record.shouldParseBackEqual()
    }

    "migration: a data class property's fixed only changes namespace, which Apache's schema resolution accepts both ways" {
        // The full name before the change was `ns.id`: the property's name in the record's namespace
        val before = Schema.Parser().parse("""{"type":"record","name":"A","namespace":"ns","fields":[{"name":"id","type":{"type":"fixed","name":"id","size":4}}]}""")
        val after = Avro.apacheSchema<RecordA>()
        after.getField("id").schema().fullName shouldBe "ns.A.id"
        compatibility(reader = after, writer = before) shouldBe SchemaCompatibilityType.COMPATIBLE
        compatibility(reader = before, writer = after) shouldBe SchemaCompatibilityType.COMPATIBLE

        // Also as a union branch
        val nullableBefore =
            Schema.Parser().parse("""{"type":"record","name":"N","namespace":"ns","fields":[{"name":"id","type":["null",{"type":"fixed","name":"id","size":4}]}]}""")
        val nullableAfter = Avro.apacheSchema<NullableFixedField>()
        compatibility(reader = nullableAfter, writer = nullableBefore) shouldBe SchemaCompatibilityType.COMPATIBLE
        compatibility(reader = nullableBefore, writer = nullableAfter) shouldBe SchemaCompatibilityType.COMPATIBLE
    }

    "migration: a value class's fixed gets a new simple name, which Apache's schema resolution only accepts with the old full name as an alias" {
        // The full name before the change was the value class property's name in the value class's namespace
        val before = Schema.Parser().parse(
            """{"type":"record","name":"V","namespace":"ns","fields":[{"name":"hash","type":{"type":"fixed","name":"value","namespace":"ns","size":4}}]}"""
        )
        // One Avro instance per schema: these three records have the same serial name, so their descriptors are equal and
        // would share one schema cache entry
        compatibility(reader = Avro {}.apacheSchema<RenamedByValueClass>(), writer = before) shouldBe SchemaCompatibilityType.INCOMPATIBLE
        compatibility(reader = Avro {}.apacheSchema<RenamedByValueClassWithAlias>(), writer = before) shouldBe SchemaCompatibilityType.COMPATIBLE
        // Or the value class keeps the old full name with @SerialName
        val kept = Avro {}.apacheSchema<KeptByValueClass>()
        kept.getField("hash").schema().fullName shouldBe "ns.value"
        compatibility(reader = kept, writer = before) shouldBe SchemaCompatibilityType.COMPATIBLE
    }

    "String fixed follows the same naming" {
        val record = Avro.apacheSchema<StringFixed>()
        record.getField("field").schema().fullName shouldBe "ns.StringFixed.field"
        record.getField("valueClass").schema().fullName shouldBe "ns.StringHash"
        record.shouldParseBackEqual()
    }
}) {
    @JvmInline
    @Serializable
    private value class DefaultNamedHash(
        @AvroFixed(4) val value: ByteArray,
    )

    @JvmInline
    @Serializable
    @SerialName("com.example.Hash")
    @AvroAlias("OldHash", "other.ns.LegacyHash")
    @AvroDoc("a hash")
    private value class CustomizedHash(
        @AvroFixed(4) val value: ByteArray,
    )

    @JvmInline
    @Serializable
    @SerialName("NoNamespaceHash")
    @AvroAlias("OldNoNamespaceHash")
    private value class NoNamespaceHash(
        @AvroFixed(4) val value: ByteArray,
    )

    @Serializable
    @SerialName("ns.RecordWithNoNamespaceHash")
    private data class RecordWithNoNamespaceHash(
        val hash: NoNamespaceHash,
    )

    @Serializable
    @SerialName("ns.A")
    private data class RecordA(
        @AvroFixed(4) val id: ByteArray,
    )

    @Serializable
    @SerialName("ns.B")
    private data class RecordB(
        @AvroFixed(8) val id: ByteArray,
    )

    @Serializable
    @SerialName("NoNamespaceRecord")
    private data class NoNamespaceRecord(
        @AvroFixed(4) val id: ByteArray,
    )

    @Serializable
    @SerialName("ns.TwoRecordsWithSameFixedFieldName")
    private data class TwoRecordsWithSameFixedFieldName(
        val a: RecordA,
        val b: RecordB,
    )

    @Serializable
    @SerialName("ns.FieldOverridesValueClass")
    private data class FieldOverridesValueClass(
        @AvroFixed(8) val hash: CustomizedHash,
    )

    @JvmInline
    @Serializable
    private value class WrappedHash(
        val hash: CustomizedHash,
    )

    @JvmInline
    @Serializable
    private value class PlainBytes(
        val bytes: ByteArray,
    )

    @JvmInline
    @Serializable
    @SerialName("ns.OuterFixed")
    private value class OuterFixed(
        @AvroFixed(3) val bytes: PlainBytes,
    )

    @Serializable
    @SerialName("ns.ValueClassUses")
    private data class ValueClassUses(
        val nullable: CustomizedHash?,
        val list: List<CustomizedHash>,
        val map: Map<String, CustomizedHash>,
        val wrapped: WrappedHash,
        val outer: OuterFixed,
    )

    @Serializable
    @SerialName("ns.HolderA")
    private data class HolderA(
        val hash: CustomizedHash,
    )

    @Serializable
    @SerialName("ns.HolderB")
    private data class HolderB(
        val hash: CustomizedHash,
    )

    @Serializable
    @SerialName("ns.TwoRecordsWithSameValueClass")
    private data class TwoRecordsWithSameValueClass(
        val a: HolderA,
        val b: HolderB,
    )

    @JvmInline
    @Serializable
    @SerialName("ns.Conflict")
    private value class Conflict4(
        @AvroFixed(4) val value: ByteArray,
    )

    @JvmInline
    @Serializable
    @SerialName("ns.Conflict")
    private value class Conflict8(
        @AvroFixed(8) val value: ByteArray,
    )

    @Serializable
    @SerialName("ns.ConflictingValueClasses")
    private data class ConflictingValueClasses(
        val a: Conflict4,
        val b: Conflict8,
    )

    @JvmInline
    @Serializable
    @SerialName("ns.Clash")
    private value class ClashFixed(
        @AvroFixed(4) val value: ByteArray,
    )

    @Serializable
    @SerialName("ns.Clash")
    private data class ClashRecord(
        val a: Int,
    )

    @Serializable
    @SerialName("ns.RecordClashingWithFixed")
    private data class RecordClashingWithFixed(
        val fixed: ClashFixed,
        val record: ClashRecord,
    )

    @JvmInline
    @Serializable
    @SerialName("ns.TheUuid")
    @AvroAlias("OldUuid")
    @AvroDoc("a uuid")
    private value class TheUuid(
        @Contextual @AvroFixed(16) val value: UUID,
    )

    @JvmInline
    @Serializable
    @SerialName("ns.TheDecimal")
    @AvroAlias("OldDecimal")
    @AvroDoc("a decimal")
    private value class TheDecimal(
        @Contextual @AvroFixed(8) @AvroDecimal(scale = 2, precision = 5) val value: BigDecimal,
    )

    @Serializable
    @SerialName("ns.LogicalTypeFixed")
    private data class LogicalTypeFixed(
        @Contextual @AvroFixed(16) val uuid: UUID,
        @AvroFixed(16) val kotlinUuid: Uuid,
        @Contextual @AvroFixed(8) @AvroDecimal(scale = 2, precision = 5) val decimal: BigDecimal,
        val uuidValueClass: TheUuid,
        val decimalValueClass: TheDecimal,
    )

    @Serializable
    @SerialName("ns.N")
    private data class NullableFixedField(
        @AvroFixed(4) val id: ByteArray?,
    )

    @JvmInline
    @Serializable
    @SerialName("ns.Hash")
    private value class NewHash(
        @AvroFixed(4) val value: ByteArray,
    )

    @JvmInline
    @Serializable
    @SerialName("ns.Hash")
    @AvroAlias("ns.value")
    private value class NewHashWithAlias(
        @AvroFixed(4) val value: ByteArray,
    )

    @JvmInline
    @Serializable
    @SerialName("ns.value")
    private value class OldNamedHash(
        @AvroFixed(4) val value: ByteArray,
    )

    @Serializable
    @SerialName("ns.V")
    private data class RenamedByValueClass(
        val hash: NewHash,
    )

    @Serializable
    @SerialName("ns.V")
    private data class RenamedByValueClassWithAlias(
        val hash: NewHashWithAlias,
    )

    @Serializable
    @SerialName("ns.V")
    private data class KeptByValueClass(
        val hash: OldNamedHash,
    )

    @JvmInline
    @Serializable
    @SerialName("ns.StringHash")
    private value class StringHash(
        @AvroFixed(5) val value: String,
    )

    @Serializable
    @SerialName("ns.StringFixed")
    private data class StringFixed(
        @AvroFixed(5) val field: String,
        val valueClass: StringHash,
    )
}

private fun compatibility(
    reader: Schema,
    writer: Schema,
): SchemaCompatibilityType = SchemaCompatibility.checkReaderWriterCompatibility(reader, writer).type

/** Apache oracle: the json of the generated schema parses back, without "Can't redefine", to an equal schema. */
private fun Schema.shouldParseBackEqual() {
    withClue(toString()) {
        Schema.Parser().parse(toString()) shouldBe this
    }
}