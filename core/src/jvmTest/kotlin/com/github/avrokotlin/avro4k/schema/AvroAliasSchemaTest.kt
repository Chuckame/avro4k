package com.github.avrokotlin.avro4k.schema

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroAlias
import com.github.avrokotlin.avro4k.AvroAssertions
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.schema
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.io.path.Path

internal class AvroAliasSchemaTest : WordSpec({

    "SchemaEncoder" should {
        "support alias annotations on types" {
            AvroAssertions.assertThat<TypeAnnotated>()
                .generatesSchema(Path("/aliases_on_types.json"))
        }
        "support multiple alias annotations on types" {
            AvroAssertions.assertThat<TypeAliasAnnotated>()
                .generatesSchema(Path("/aliases_on_types_multiple.json"))
        }
        "support alias annotations on field" {
            AvroAssertions.assertThat<FieldAnnotated>()
                .generatesSchema(Path("/aliases_on_fields.json"))
        }
        "support multiple alias annotations on fields" {
            AvroAssertions.assertThat<FieldAliasAnnotated>()
                .generatesSchema(Path("/aliases_on_fields_multiple.json"))
        }
        "drop an alias equal to the own name of a record, an enum or a field, as Apache does not reject it" {
            val record = Avro.schema<SelfAliased>() as AvroSchema.RecordSchema
            record.aliases shouldBe setOf(Name("com.example.Old"))
            record.fields[0].aliases shouldBe setOf("old")
            (record.fields[1].schema as AvroSchema.EnumSchema).aliases shouldBe emptySet()
        }
    }
}) {
    @Serializable
    @AvroAlias("queen")
    private data class TypeAnnotated(val str: String)

    @AvroAlias("queen", "ledzep")
    @Serializable
    private data class TypeAliasAnnotated(val str: String)

    @Serializable
    private data class FieldAnnotated(
        @AvroAlias("cold") val str: String,
        @AvroAlias("kate") val long: Long,
        val int: IntValue,
    )

    @Serializable
    @JvmInline
    private value class IntValue(
        @AvroAlias("ignoredAlias") val value: Int,
    )

    @Serializable
    @SerialName("com.example.SelfAliased")
    @AvroAlias("SelfAliased", "Old")
    private data class SelfAliased(
        @AvroAlias("str", "old") val str: String,
        val enum: SelfAliasedEnum,
    )

    @Serializable
    @SerialName("com.example.SelfAliasedEnum")
    @AvroAlias("SelfAliasedEnum")
    private enum class SelfAliasedEnum { A, }

    @Serializable
    private data class FieldAliasAnnotated(
        @AvroAlias("queen", "ledzep") val str: String,
    )
}