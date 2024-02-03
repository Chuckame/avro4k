package com.github.avrokotlin.avro4k.schema.default

import com.github.avrokotlin.avro4k.Avro
import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.math.BigDecimal
import java.time.Instant

internal class DefaultFinderTest : FunSpec({
    test("should get defaults") {
        val defaults = Avro.serializersModule.serializer<SomeDefaults>().getElementsDefaults(Avro.serializersModule)
        println(defaults)
    }
    test("should get defaults from sealed class") {
        val defaults = Avro.serializersModule.serializer<ComplexSealedDefaults>().getElementsDefaults(Avro.serializersModule)
        println(defaults)
    }
})

@Serializable
private data class SomeDefaults(
    @Contextual val contextualBigDecimal: BigDecimal = BigDecimal.valueOf(42),
    @Contextual val contextualDate: Instant = Instant.parse("2021-01-01T00:00:00Z"),
    val recursiveList: List<SomeDefaults>,
    val recursiveMap: Map<String, SomeDefaults>,
    val notDefault1: Int,
    val notDefault2: String?,
    val notDefaultObject: SomeDefaults?,
    val a: String = "a",
    val valueClassString: NestedString = NestedString("toto"),
    val valueClassOptionalString: NestedOptionalString,
    val valueClassObject: NestedObject? = NestedObject(
        SomeDefaults(
            g = null,
            h = listOf(),
            valueClassObject = null,
            notDefault1 = 1,
            notDefault2 = "a",
            valueClassString = NestedString("toto"),
            notDefaultObject = null,
            recursiveList = listOf(),
            recursiveMap = mapOf(),
            valueClassOptionalString = NestedOptionalString("toto")
        )
    ),
    val b: Int = 1,
    val c: Boolean = true,
    val d: Double = 1.0,
    val e: List<String> = listOf("a"),
    val f: Map<String, Int> = mapOf("a" to 1),
    val g: SomeDefaults? = SomeDefaults(
        g = null,
        h = listOf(),
        notDefault1 = 1,
        notDefault2 = "a",
        notDefaultObject = null,
        valueClassOptionalString = NestedOptionalString("toto"),
        recursiveMap = mapOf(),
        recursiveList = listOf()
    ),
    val h: List<SomeDefaults> = listOf(
        SomeDefaults(
            g = null,
            h = listOf(),
            notDefault1 = 1,
            notDefault2 = "a",
            notDefaultObject = null,
            valueClassOptionalString = NestedOptionalString("toto"),
            recursiveMap = mapOf(),
            recursiveList = listOf()
        )
    ),
)

@Serializable
private data class ComplexSealedDefaults(
    val data: SuperClass
)

@Serializable
private sealed interface SuperClass {
    @Serializable
    data class OtherSubClass(val a: String = "a") : SuperClass

    @Serializable
    object DefaultSubClass : SuperClass
}

@JvmInline
@Serializable
private value class NestedString(val s: String)

@JvmInline
@Serializable
private value class NestedOptionalString(val s: String = "optional")

@JvmInline
@Serializable
private value class NestedObject(val s: SomeDefaults)
