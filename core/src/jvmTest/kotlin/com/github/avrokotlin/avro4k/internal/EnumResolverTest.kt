package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroEnumDefault
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Regression tests for [EnumResolver], which stores the resolved default index as a primitive `Int`
 * with a `-1` sentinel instead of a boxed `Int?`.
 */
internal class EnumResolverTest : StringSpec({
    "resolves the index of the @AvroEnumDefault element, and caches it" {
        val resolver = EnumResolver()
        val descriptor = serializer<EnumWithLateDefault>().descriptor

        repeat(3) {
            resolver.getDefaultValueIndex(descriptor) shouldBe 2
        }
    }

    "resolves the first element as a default when it is the annotated one" {
        val resolver = EnumResolver()
        val descriptor = serializer<EnumWithFirstDefault>().descriptor

        // index 0 must not be confused with the "no default" sentinel
        repeat(3) {
            resolver.getDefaultValueIndex(descriptor) shouldBe 0
        }
    }

    "returns null when the enum has no @AvroEnumDefault element, on every call" {
        val resolver = EnumResolver()
        val descriptor = serializer<EnumWithoutDefault>().descriptor

        repeat(3) {
            resolver.getDefaultValueIndex(descriptor) shouldBe null
        }
    }

    "keeps separate defaults per descriptor" {
        val resolver = EnumResolver()

        resolver.getDefaultValueIndex(serializer<EnumWithLateDefault>().descriptor) shouldBe 2
        resolver.getDefaultValueIndex(serializer<EnumWithoutDefault>().descriptor) shouldBe null
        resolver.getDefaultValueIndex(serializer<EnumWithFirstDefault>().descriptor) shouldBe 0
        resolver.getDefaultValueIndex(serializer<EnumWithLateDefault>().descriptor) shouldBe 2
    }

    "fails on every call when the enum has multiple @AvroEnumDefault elements" {
        val resolver = EnumResolver()
        val descriptor = serializer<EnumWithManyDefaults>().descriptor

        repeat(2) {
            shouldThrow<UnsupportedOperationException> {
                resolver.getDefaultValueIndex(descriptor)
            }.message shouldBe "Multiple default values found in enum $descriptor"
        }
    }
}) {
    @Serializable
    private enum class EnumWithLateDefault {
        A,
        B,

        @AvroEnumDefault
        UNKNOWN,
    }

    @Serializable
    private enum class EnumWithFirstDefault {
        @AvroEnumDefault
        UNKNOWN,
        A,
    }

    @Serializable
    private enum class EnumWithoutDefault {
        A,
        B,
    }

    @Serializable
    private enum class EnumWithManyDefaults {
        @AvroEnumDefault
        DEF1,

        @AvroEnumDefault
        DEF2,
    }
}