package com.github.avrokotlin.avro4k

import com.squareup.kotlinpoet.Annotatable
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.Documentable
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.MemberName
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.joinToCode
import com.squareup.kotlinpoet.tag

/**
 * Adds the properties to the class and sets the primary constructor with one parameter per property, in the same order.
 * This is helpful for data or value classes where properties are typically defined in the primary constructor.
 *
 * Each property is paired with the default value of its constructor parameter, or `null` if there is no default value.
 * Any previously set primary constructor is replaced.
 */
internal fun TypeSpec.Builder.addPrimaryProperties(properties: List<Pair<PropertySpec, CodeBlock?>>): TypeSpec.Builder {
    if (properties.isEmpty()) return this
    return addProperties(properties.map { (property, _) -> property.toBuilder().initializer(property.name).build() })
        .primaryConstructor(
            FunSpec.constructorBuilder()
                .addParameters(
                    properties.map { (property, defaultValue) ->
                        ParameterSpec.builder(property.name, property.type)
                            .defaultValue(defaultValue)
                            .build()
                    }
                )
                .build()
        )
}

/**
 * Generates equals and hashCode functions, while comparing ByteArray properties with contentEquals and contentHashCode
 * to avoid reference equality issues. The properties typed with one of [byteArrayValueClasses] (value classes wrapping a
 * ByteArray `value`, nullable or not) are compared on their `value` the same way.
 */
internal fun TypeSpec.Builder.addEqualsHashCode(
    className: ClassName,
    byteArrayValueClasses: Set<ClassName> = emptySet(),
): TypeSpec.Builder {
    val byteArrayType = ByteArray::class.asClassName()
    val byteArrayValueClassTypes = byteArrayValueClasses.flatMapTo(mutableSetOf()) { listOf(it, it.copy(nullable = true)) }
    if (propertySpecs.none { it.type == byteArrayType || it.type in byteArrayValueClassTypes }) {
        // we only generate equals/hashcode if a field has ByteArray type
        return this
    }

    /** The expression to compare or hash by content, or null to compare or hash the property itself. */
    fun contentOf(
        property: PropertySpec,
        receiver: String,
    ): CodeBlock? =
        when {
            property.type == byteArrayType -> CodeBlock.of("%L%N", receiver, property.name)
            property.type in byteArrayValueClassTypes && property.type.isNullable -> CodeBlock.of("%L%N?.value", receiver, property.name)
            property.type in byteArrayValueClassTypes -> CodeBlock.of("%L%N.value", receiver, property.name)
            else -> null
        }
    addFunction(
        FunSpec.builder("equals")
            .addModifiers(KModifier.OVERRIDE)
            .addParameter("other", Any::class.asClassName().copy(nullable = true))
            .returns(Boolean::class)
            .addStatement("if (this === other) return true")
            .addStatement("if (javaClass != other?.javaClass) return false")
            .addStatement("other as %T", className)
            .apply {
                propertySpecs.forEach { property ->
                    val content = contentOf(property, "")
                    if (content != null) {
                        addStatement("if (!%L.contentEquals(%L)) return false", content, contentOf(property, "other.")!!)
                    } else {
                        addStatement("if (%N != other.%N) return false", property.name, property.name)
                    }
                }
            }
            .addStatement("return true")
            .build()
    )
    addFunction(
        FunSpec.builder("hashCode")
            .addModifiers(KModifier.OVERRIDE)
            .returns(Int::class)
            .apply {
                fun hashCodeOf(property: PropertySpec): CodeBlock =
                    contentOf(property, "")?.let { CodeBlock.of("%L.contentHashCode()", it) } ?: CodeBlock.of("%N.hashCode()", property.name)
                propertySpecs.first().let { property ->
                    addStatement("var result = %L", hashCodeOf(property))
                }
                propertySpecs.drop(1).forEach { property ->
                    addStatement("result = 31 * result + %L", hashCodeOf(property))
                }
                addStatement("return result")
            }
            .build()
    )
    return this
}

internal fun TypeSpec.withAnnotation(annotation: AnnotationSpec): TypeSpec {
    return toBuilder()
        .addAnnotation(annotation)
        .build()
}

internal fun <T : Annotatable.Builder<T>> T.addAnnotationIfNotNull(annotation: AnnotationSpec?): T {
    return annotation?.let { addAnnotation(it) } ?: this
}

internal fun <T : Documentable.Builder<T>> T.addKDocIfNotNull(doc: String?): T {
    return doc?.let { addKdoc(it) } ?: this
}

internal fun getMapOfCodeBlock(map: Map<String, CodeBlock>): CodeBlock =
    if (map.isNotEmpty()) {
        CodeBlock.of(
            "%M(%L)",
            MemberName("kotlin.collections", "mapOf"),
            map.map { (key, value) -> CodeBlock.of("%S to %L", key, value) }.joinToCode()
        )
    } else {
        CodeBlock.of("%M()", MemberName("kotlin.collections", "emptyMap"))
    }

internal fun getListOfCodeBlock(list: List<CodeBlock>): CodeBlock =
    if (list.isNotEmpty()) {
        CodeBlock.of("%M(%L)", MemberName("kotlin.collections", "listOf"), list.joinToCode())
    } else {
        CodeBlock.of("%M()", MemberName("kotlin.collections", "emptyList"))
    }

internal fun getKotlinClassReplacement(className: String): ClassName? =
    when (className) {
        String::class.java.name -> String::class.asClassName()
        Boolean::class.javaObjectType.name, Boolean::class.javaPrimitiveType!!.name -> Boolean::class.asClassName()
        Char::class.javaObjectType.name, Char::class.javaPrimitiveType!!.name -> Char::class.asClassName()
        Byte::class.javaObjectType.name, Byte::class.javaPrimitiveType!!.name -> Byte::class.asClassName()
        Short::class.javaObjectType.name, Short::class.javaPrimitiveType!!.name -> Short::class.asClassName()
        Int::class.javaObjectType.name, Int::class.javaPrimitiveType!!.name -> Int::class.asClassName()
        Long::class.javaObjectType.name, Long::class.javaPrimitiveType!!.name -> Long::class.asClassName()
        Float::class.javaObjectType.name, Float::class.javaPrimitiveType!!.name -> Float::class.asClassName()
        Double::class.javaObjectType.name, Double::class.javaPrimitiveType!!.name -> Double::class.asClassName()
        else -> null
    }

internal fun ClassName.Companion.fromFullName(fullName: String) =
    ClassName.bestGuess(fullName)

internal fun TypeName.nullableIf(toBeNullable: Boolean): TypeName {
    if (toBeNullable == isNullable) return this
    return copy(nullable = toBeNullable)
}

/**
 * The opt-in markers (their full class names) that a file naming the tagged type must opt in to. A tag is side-channel data:
 * it is kept by [TypeName.copy] (nullability, annotations) and by the type arguments of a parameterized type, and it is
 * ignored by [TypeName.equals].
 */
private class RequiredOptIns(val markerClassNames: Set<String>)

internal fun TypeName.withRequiredOptIns(markerClassNames: Collection<String>): TypeName {
    if (markerClassNames.isEmpty()) return this
    val allMarkers = requiredOptIns() + markerClassNames
    return copy(tags = tags + (RequiredOptIns::class to RequiredOptIns(allMarkers)))
}

private fun TypeName.requiredOptIns(): Set<String> {
    val own = tag<RequiredOptIns>()?.markerClassNames.orEmpty()
    // The generator only builds class names and parameterized types (lists and maps)
    return when (this) {
        is ParameterizedTypeName -> own + rawType.requiredOptIns() + typeArguments.flatMap { it.requiredOptIns() }
        else -> own
    }
}

/** The opt-in markers required by the types this type, its members and its nested types name. */
internal fun TypeSpec.requiredOptIns(): Set<String> =
    buildSet {
        propertySpecs.forEach { addAll(it.type.requiredOptIns()) }
        (funSpecs + listOfNotNull(primaryConstructor)).forEach { function ->
            function.parameters.forEach { addAll(it.type.requiredOptIns()) }
            addAll(function.returnType.requiredOptIns())
        }
        addAll(superclass.requiredOptIns())
        superinterfaces.keys.forEach { addAll(it.requiredOptIns()) }
        typeSpecs.forEach { addAll(it.requiredOptIns()) }
    }