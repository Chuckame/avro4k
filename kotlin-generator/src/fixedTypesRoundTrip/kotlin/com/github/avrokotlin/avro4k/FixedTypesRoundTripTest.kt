package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.AvroGenerated
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.serializer
import org.apache.avro.Schema
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The generated Kotlin code gives back every fixed of its source schema: same full name, aliases, doc and size.
 *
 * The expected sources of `fixed-types.avsc` are compiled without their `@AvroGenerated` annotations (see the
 * `fixedTypesWithoutAvroGenerated` task), as `Avro.schema()` would otherwise return the source schema verbatim, and the
 * test would prove nothing. So the schema below is the one avro4k infers from the generated classes alone.
 */
class FixedTypesRoundTripTest {
    @Test
    fun `the schema inferred from the generated classes has the source schema's fixed types`() {
        val source = Schema.Parser().parse(File("src/test/resources/fixed-types.avsc"))
        val descriptor = serializer(Class.forName("fixed.ns.FixedTypes")).descriptor

        val inferred = Avro.schema(descriptor)

        inferred.fixedTypes() shouldBe source.fixedTypes()
        // Inferred indeed: no generated class carries its schema
        descriptor.withNestedDescriptors().forEach { it.annotations.filterIsInstance<AvroGenerated>().shouldBeEmpty() }
        // All the source's fixed types, also the ones only used in a union, an array or a map
        source.fixedTypes().keys shouldBe
            setOf(
                "fixed.ns.Plain",
                "other.ns.Documented",
                "fixed.ns.NullableFixed",
                "items.ns.Item",
                "NoNamespace",
                "fixed.ns.TheUuid",
                "fixed.ns.TheDecimal",
                "fixed.ns.WithDefault",
                "fixed.ns.UnionOnly"
            )
    }

    private fun SerialDescriptor.withNestedDescriptors(visited: MutableSet<String> = mutableSetOf()): List<SerialDescriptor> {
        if (!visited.add(serialName)) return emptyList()
        return listOf(this) + elementDescriptors.flatMap { it.withNestedDescriptors(visited) }
    }

    private data class FixedType(
        val aliases: Set<String>,
        val doc: String?,
        val size: Int,
        val logicalType: String?,
    )

    private fun Schema.fixedTypes(): Map<String, FixedType> = mutableMapOf<String, FixedType>().also { collectFixedTypes(it, mutableSetOf()) }

    private fun Schema.collectFixedTypes(
        fixedTypes: MutableMap<String, FixedType>,
        visitedRecords: MutableSet<String>,
    ) {
        when (type) {
            Schema.Type.FIXED -> fixedTypes[fullName] = FixedType(aliases, doc, fixedSize, logicalType?.name)
            Schema.Type.RECORD -> if (visitedRecords.add(fullName)) fields.forEach { it.schema().collectFixedTypes(fixedTypes, visitedRecords) }
            Schema.Type.UNION -> types.forEach { it.collectFixedTypes(fixedTypes, visitedRecords) }
            Schema.Type.ARRAY -> elementType.collectFixedTypes(fixedTypes, visitedRecords)
            Schema.Type.MAP -> valueType.collectFixedTypes(fixedTypes, visitedRecords)
            else -> {}
        }
    }
}