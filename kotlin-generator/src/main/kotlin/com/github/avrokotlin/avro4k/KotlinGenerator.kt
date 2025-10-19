package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.SerializableTypeName.Companion.addSerializableAnnotation
import com.squareup.kotlinpoet.Annotatable
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

/**
 * Generates Kotlin classes from Avro schemas, fully compatible with avro4k.
 *
 * @param avro The Avro configuration to use mainly for logical types mapping.
 * @param unionNameFormatter A function to format the name of the generated sealed interface for union types. The default implementation appends "Union" to the provided base name.
 * @param logicalTypes Provides a way to specify additional logical types that should be materialized with specific Kotlin classes and serializers.
 */
@InternalAvro4kApi
public class KotlinGenerator(
    private val avro: Avro = Avro,
    private val unionNameFormatter: (String) -> String = { "${it}Union" },
    private val unionSubTypeNameFormatter: (String) -> String = { "For$it" },
    logicalTypes: List<LogicalTypeDescriptor> = emptyList(),
) {
    @InternalAvro4kApi
    public data class LogicalTypeDescriptor(
        val logicalTypeName: String,
        val kotlinClassName: String,
        val kSerializerClassName: String? = null,
    )

    private val logicalTypes: Map<String, SerializableTypeName> =
        avro.configuration.logicalTypes.mapValues {
            SerializableTypeName(
                typeName = ClassName.bestGuess(it.value.descriptor.serialName),
                serializableAnnotation =
                    AnnotationSpec.builder(Serializable::class)
                        .addMember("${Serializable::with.name} = %T::class", it.value::class.asClassName())
                        .build()
            )
        } +
            logicalTypes.associate {
                it.logicalTypeName to
                    run {
                        val typeName = parseJavaClassName(it.kotlinClassName)
                        if (it.kSerializerClassName != null) {
                            SerializableTypeName(
                                typeName = typeName.typeName,
                                serializableAnnotation =
                                    AnnotationSpec.builder(Serializable::class)
                                        .addMember("${Serializable::with.name} = %T::class", ClassName.bestGuess(it.kSerializerClassName))
                                        .build()
                            )
                        } else {
                            typeName
                        }
                    }
            }

    /**
     * Generates Kotlin classes from the provided Avro schema.
     *
     * @param schema The Avro schema as a JSON string.
     * @param rootAnonymousSchemaName The base name to use for the root schema if it does not have a name (any schema except record, enum or fixed).
     */
    public fun generateKotlinClasses(schema: String, rootAnonymousSchemaName: String): List<FileSpec> {
        return generateRootKotlinClasses(TypeSafeSchema.from(schema), rootAnonymousSchemaName.toPascalCase())
    }

    private fun generateRootKotlinClasses(
        schema: TypeSafeSchema,
        potentialAnonymousClassName: String,
    ): List<FileSpec> {
        schema.actualJavaClassName?.let {
            return listOf(generateRootValueClassFile(schema, potentialAnonymousClassName, parseJavaClassName(it).nullableIf(schema.isNullable)))
        }
        schema.getLogicalTypeName()?.let {
            return listOf(generateRootValueClassFile(schema, potentialAnonymousClassName, it))
        }

        val output = mutableListOf<FileSpec>()
        when (schema) {
            is TypeSafeSchema.UnionSchema -> {
                output += generateUnionInterface(schema, potentialAnonymousClassName).toFileSpec()
            }

            is TypeSafeSchema.CollectionSchema -> {
                val nestedUnionType = schema.nestedUnionToGenerateIfAny()?.let { generateUnionInterface(it, unionNameFormatter("Value")) }
                output += generateRootValueClassFile(schema, potentialAnonymousClassName, getTypeName(schema) { nestedUnionType?.name }, nestedUnionType)
            }

            is TypeSafeSchema.NamedSchema.FixedSchema ->
                output += generateRootValueClassFile(schema, potentialAnonymousClassName, getTypeName(schema))

            is TypeSafeSchema.PrimitiveSchema ->
                output += generateRootValueClassFile(schema, potentialAnonymousClassName, getTypeName(schema))

            else -> {
                // no more specific type to generate at root level
            }
        }
        output += generateNestedKotlinClasses(schema, emptySet())
        return output
    }

    private fun generateRootValueClassFile(schema: TypeSafeSchema, className: String, wrappedType: SerializableTypeName, nestedType: TypeSpec? = null): FileSpec {
        return TypeSpec.classBuilder(className)
            .addModifiers(KModifier.VALUE)
            .addAnnotation(JvmInline::class)
            .addAnnotation(Serializable::class)
            .addPrimaryProperty(
                // TODO we may have nullable primitive, but we have a non-null original schema in buildAvroGeneratedAnnotation
                PropertySpec.builder("value", wrappedType.typeName)
                    .addAnnotationIfNotNull(buildAvroDecimalAnnotation(schema))
                    .addAnnotationIfNotNull(buildAvroFixedAnnotation(schema))
                    .addAnnotations(buildAvroPropAnnotations(schema))
                    .addAnnotationIfNotNull(buildImplicitAvroDefaultAnnotation(schema, avro.configuration))
                    .addSerializableAnnotation(wrappedType)
                    .build(),
                defaultValue = buildImplicitAvroDefaultCodeBlock(schema, avro.configuration)
            )
            .addAnnotation(buildAvroGeneratedAnnotation(schema))
            .addTypeIfNotNull(nestedType)
            .build()
            .toFileSpec()
    }

    private fun generateNestedKotlinClasses(
        schema: TypeSafeSchema,
        generatedRecords: Set<ClassName>,
    ): List<FileSpec> {
        schema.actualJavaClassName?.let {
            // nothing to generate except the root value class wrapping the already existing logical type
            return emptyList()
        }
        schema.getLogicalTypeName()?.let {
            // nothing to generate except the root value class wrapping the already existing logical type
            return emptyList()
        }
        return when (schema) {
            is TypeSafeSchema.NamedSchema.RecordSchema -> {
                val recordTypeName = schema.asClassName()
                if (recordTypeName in generatedRecords) {
                    // recursive schema
                    emptyList()
                } else {
                    listOf(generateRecordClassFile(schema)) +
                        // generate nested types
                        schema.fields.flatMap { field ->
                            // the union schema is already generated as a subtype of the record,
                            // no need to generate it again. However, we need to generate the types used in the union

                            field.schema.nestedUnionToGenerateIfAny()
                                ?.let { it.types.flatMap { generateNestedKotlinClasses(it, generatedRecords) } }
                                ?: generateNestedKotlinClasses(field.schema, generatedRecords + recordTypeName)
                        }
                }
            }

            is TypeSafeSchema.NamedSchema.EnumSchema ->
                listOf(generateEnumClassFile(schema))

            is TypeSafeSchema.UnionSchema ->
                schema.types.flatMap { generateNestedKotlinClasses(it, generatedRecords) }

            is TypeSafeSchema.CollectionSchema.ArraySchema -> {
                if (schema.actualElementClass != null) {
                    // assuming the class already exists, nothing to generate
                    emptyList()
                } else {
                    generateNestedKotlinClasses(schema.elementSchema, generatedRecords)
                }
            }

            is TypeSafeSchema.CollectionSchema.MapSchema ->
                generateNestedKotlinClasses(schema.valueSchema, generatedRecords)

            // non-root fixed types are ByteArray fields with @AvroFixed, so nothing to generate here
            is TypeSafeSchema.NamedSchema.FixedSchema,
            is TypeSafeSchema.PrimitiveSchema,
            -> emptyList()
        }
    }

    private fun TypeSafeSchema.getLogicalTypeName(): SerializableTypeName? {
        return logicalTypeName?.let { this@KotlinGenerator.logicalTypes[it] }?.nullableIf(this.isNullable)
    }

    private fun getTypeName(schema: TypeSafeSchema, potentialUnionName: () -> String? = { null }): SerializableTypeName {
        schema.actualJavaClassName?.let {
            return parseJavaClassName(it).nullableIf(schema.isNullable)
        }
        schema.getLogicalTypeName()?.let {
            return it
        }
        return when (schema) {
            is TypeSafeSchema.NamedSchema.RecordSchema,
            is TypeSafeSchema.NamedSchema.EnumSchema,
            -> {
                @Suppress("USELESS_CAST") // this is an obvious cast, but needed to convince the compiler
                (schema as TypeSafeSchema.NamedSchema).asClassName().nativelySerializable()
            }

            is TypeSafeSchema.PrimitiveSchema.StringSchema -> String::class.asClassName().nativelySerializable()
            is TypeSafeSchema.PrimitiveSchema.IntSchema -> Int::class.asClassName().nativelySerializable()
            is TypeSafeSchema.PrimitiveSchema.LongSchema -> Long::class.asClassName().nativelySerializable()
            is TypeSafeSchema.PrimitiveSchema.BooleanSchema -> Boolean::class.asClassName().nativelySerializable()
            is TypeSafeSchema.PrimitiveSchema.FloatSchema -> Float::class.asClassName().nativelySerializable()
            is TypeSafeSchema.PrimitiveSchema.DoubleSchema -> Double::class.asClassName().nativelySerializable()
            is TypeSafeSchema.PrimitiveSchema.BytesSchema,
            is TypeSafeSchema.NamedSchema.FixedSchema,
            -> ByteArray::class.asClassName().nativelySerializable()

            is TypeSafeSchema.CollectionSchema.ArraySchema -> {
                val itemType: SerializableTypeName =
                    schema.actualElementClass?.let { parseJavaClassName(it).nullableIf(schema.elementSchema.isNullable) }
                        ?: getTypeName(schema.elementSchema, potentialUnionName)

                val wrapperType = List::class.asClassName().parameterizedBy(itemType.typeName)
                if (!itemType.isNativelySerializable()) {
                    // There is no way to annotate the type argument of a List, so we let the whole List be contextual
                    wrapperType.contextual()
                } else {
                    wrapperType.nativelySerializable()
                }
            }

            is TypeSafeSchema.CollectionSchema.MapSchema -> {
                val keyType =
                    schema.actualKeyClass?.let { parseJavaClassName(it) }
                        ?: String::class.asClassName().nativelySerializable()
                val valueType = getTypeName(schema.valueSchema, potentialUnionName)

                val wrappedType =
                    Map::class.asClassName().parameterizedBy(
                        keyType.typeName,
                        valueType.typeName
                    )
                if (!keyType.isNativelySerializable() || !valueType.isNativelySerializable()) {
                    // There is no way to annotate the type argument of a Map, so we let the whole Map be contextual
                    wrappedType.contextual()
                } else {
                    wrappedType.nativelySerializable()
                }
            }

            is TypeSafeSchema.UnionSchema -> {
                // This union will be generated, so it will be natively serializable
                ClassName("", potentialUnionName() ?: error("Cannot get typeName for union schema $schema")).nativelySerializable()
            }
        }.nullableIf(schema.isNullable)
    }

    /**
     * Generates a sealed interface representing a complex union (more than one non-null type).
     *
     * ```kotlin
     * @Serializable
     * sealed interface <potentialAnonymousBaseName>Union {
     *     @JvmInline
     *     @Serializable
     *     value class For<Type name>(val value: <Type full name>) : <potentialAnonymousBaseName>Union
     *
     *     ...
     * }
     */
    private fun generateUnionInterface(
        schema: TypeSafeSchema.UnionSchema,
        className: String,
    ): TypeSpec {
        return TypeSpec.interfaceBuilder(className)
            .addModifiers(KModifier.SEALED)
            .addAnnotation(Serializable::class)
            .addTypes(
                run {
                    val hasSimilarNames = schema.types.groupBy { it.name }.any { it.value.size > 1 }
                    schema.types.map { subSchema ->
                        val typeName = getTypeName(subSchema) { unionNameFormatter("Value") }
                        TypeSpec.classBuilder(unionSubTypeNameFormatter(if (hasSimilarNames) subSchema.fullName else subSchema.name.toPascalCase()))
                            .addSuperinterface(ClassName("", className))
                            .addModifiers(KModifier.VALUE)
                            .addAnnotation(JvmInline::class)
                            .addAnnotation(Serializable::class)
                            .addPrimaryProperty(
                                PropertySpec.builder("value", typeName.typeName)
                                    .addAnnotationIfNotNull(buildAvroDecimalAnnotation(subSchema))
                                    .addAnnotationIfNotNull(buildAvroFixedAnnotation(subSchema))
                                    .addAnnotations(buildAvroPropAnnotations(subSchema))
                                    .addSerializableAnnotation(typeName)
                                    .build()
                            )
                            .addTypeIfNotNull(
                                // generate unions as nested types rather than top level types to avoid name clashes
                                subSchema.nestedUnionToGenerateIfAny()?.let { generateUnionInterface(it, unionNameFormatter("Value")) }
                            )
                            .addAnnotation(buildAvroGeneratedAnnotation(subSchema))
                            .build()
                    }
                }
            )
            .addAnnotation(buildAvroGeneratedAnnotation(schema))
            .build()
    }

    /**
     * Generates an enum class representing the Avro enum schema.
     *
     * ```kotlin
     * @Serializable
     * @AvroDoc("doc")
     * @AvroProp("customProp", "customValue")
     * @AvroAlias("alias1", "alias2")
     * enum class <Enum name> {
     *    A,
     *    @AvroEnumDefault
     *    B,
     *    C,
     * }
     * ```
     */
    private fun generateEnumClassFile(schema: TypeSafeSchema.NamedSchema.EnumSchema): FileSpec {
        return TypeSpec.enumBuilder(schema.name)
            .addAnnotation(Serializable::class)
            .addAnnotations(buildAvroPropAnnotations(schema))
            .addAnnotationIfNotNull(buildAvroDocAnnotation(schema))
            .addKDocIfNotNull(schema.doc)
            .addAnnotationIfNotNull(buildAvroAliasAnnotation(schema))
            .addAnnotation(buildAvroGeneratedAnnotation(schema))
            .apply {
                schema.symbols.forEach { enumSymbol ->
                    addEnumConstant(
                        enumSymbol,
                        TypeSpec.anonymousClassBuilder()
                            .apply {
                                if (enumSymbol == schema.defaultSymbol) {
                                    addAnnotation(AnnotationSpec.builder(AvroEnumDefault::class.asClassName()).build())
                                }
                            }
                            .build()
                    )
                }
            }
            .build()
            .toFileSpec(schema.space)
    }

    private fun generateRecordClassFile(schema: TypeSafeSchema.NamedSchema.RecordSchema): FileSpec {
        return (if (schema.fields.isNotEmpty()) TypeSpec.classBuilder(schema.name).addModifiers(KModifier.DATA) else TypeSpec.objectBuilder(schema.name))
            .addAnnotation(Serializable::class)
            .addAnnotations(buildAvroPropAnnotations(schema))
            .addAnnotationIfNotNull(buildAvroDocAnnotation(schema))
            .addKDocIfNotNull(schema.doc)
            .addAnnotationIfNotNull(buildAvroAliasAnnotation(schema))
            .addAnnotation(buildAvroGeneratedAnnotation(schema))
            .let {
                // generate record's fields
                schema.fields.fold(it) { builder, field ->
                    val typeName = getTypeName(field.schema) { unionNameFormatter(field.name.uppercaseFirstChar()) }

                    builder.addPrimaryProperty(
                        PropertySpec.builder(field.name, typeName.typeName)
                            .initializer(field.name)
                            .addAnnotations(buildAvroPropAnnotations(field))
                            .addAnnotationIfNotNull(buildAvroDocAnnotation(field))
                            .addKDocIfNotNull(field.doc)
                            .apply {
                                if (field.hasDefaultValue()) {
                                    if (kdoc.isNotEmpty()) {
                                        addKdoc("\n\n")
                                    }
                                    val defaultStr =
                                        when (val default = field.defaultValue) {
                                            is ByteArray -> default.contentToString()
                                            else -> default.toString()
                                        }
                                    addKdoc("Default value: $defaultStr")
                                }
                            }
                            .addAnnotationIfNotNull(buildAvroAliasAnnotation(field))
                            .addAnnotationIfNotNull(buildAvroDecimalAnnotation(field.schema))
                            .addAnnotationIfNotNull(buildAvroFixedAnnotation(field.schema))
                            .addAnnotationIfNotNull(buildAvroDefaultAnnotation(field))
                            .addSerializableAnnotation(typeName)
                            .build(),
                        defaultValue =
                            if (field.hasDefaultValue()) {
                                if (typeName.isNativelySerializable()) {
                                    // TODO recursive types needs to have a default value, or it's not possible to instantiate them
                                    getRecordFieldDefault(field.schema, field.defaultValue)
                                } else {
                                    // TODO contextual types needs to be converted to match well the default value
                                    null
                                }
                            } else {
                                buildImplicitAvroDefaultCodeBlock(field.schema, avro.configuration)
                            }
                    )
                }
            }
            .addTypes(
                // generate fields' unions as nested types rather than top level types to avoid name clashes
                schema.fields.mapNotNull { field ->
                    val nestedUnion = field.schema.nestedUnionToGenerateIfAny() ?: return@mapNotNull null
                    generateUnionInterface(nestedUnion, unionNameFormatter(field.name.uppercaseFirstChar()))
                }
            )
            .addEqualsHashCode(schema.asClassName())
            .build()
            .toFileSpec(schema.space)
    }

    private fun getRecordFieldDefault(schema: TypeSafeSchema, fieldDefault: Any?): CodeBlock? {
        if (fieldDefault == null && schema.isNullable) return CodeBlock.of("null")

        return when (schema) {
            is TypeSafeSchema.NamedSchema.FixedSchema,
            is TypeSafeSchema.PrimitiveSchema.BytesSchema,
            -> CodeBlock.of("byteArrayOf(%L)", (fieldDefault as ByteArray).joinToString(", "))

            is TypeSafeSchema.NamedSchema.EnumSchema,
            is TypeSafeSchema.PrimitiveSchema.StringSchema,
            -> CodeBlock.of("%S", fieldDefault)

            is TypeSafeSchema.PrimitiveSchema.BooleanSchema -> CodeBlock.of("%L", fieldDefault as Boolean)
            is TypeSafeSchema.PrimitiveSchema.DoubleSchema -> CodeBlock.of("%L", fieldDefault as Double)
            is TypeSafeSchema.PrimitiveSchema.FloatSchema -> CodeBlock.of("%L", "${fieldDefault}f")
            is TypeSafeSchema.PrimitiveSchema.IntSchema -> CodeBlock.of("%L", fieldDefault as Int)
            is TypeSafeSchema.PrimitiveSchema.LongSchema -> CodeBlock.of("%L", fieldDefault as Long)

            // for union, the default has to be of the first type
            is TypeSafeSchema.UnionSchema -> getRecordFieldDefault(schema.types.first(), fieldDefault)

            is TypeSafeSchema.CollectionSchema.ArraySchema ->
                @Suppress("UNCHECKED_CAST")
                (fieldDefault as List<*>)
                    .map { getRecordFieldDefault(schema.elementSchema, it) }
                    .takeIf { it.all { it != null } }
                    ?.let {
                        getListOfCodeBlock(it as List<CodeBlock>)
                    }

            is TypeSafeSchema.CollectionSchema.MapSchema ->
                @Suppress("UNCHECKED_CAST")
                (fieldDefault as Map<*, *>)
                    .mapValues { getRecordFieldDefault(schema.valueSchema, it.value) }
                    .takeIf { it.all { it.key is String && it.value != null } }
                    ?.let { getMapOfCodeBlock(it as Map<String, CodeBlock>) }

            // TODO records' defaults are Maps, which needs to be converted to the actual record class instance
            is TypeSafeSchema.NamedSchema.RecordSchema -> null
        }
    }

    private fun TypeSpec.toFileSpec(namespace: String? = null): FileSpec {
        return FileSpec.builder(namespace?.takeIf { it.isNotEmpty() } ?: "", name!!)
            // @file:OptIn(InternalAvro4kApi::class, ExperimentalAvro4kApi::class)
            .addAnnotation(
                AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                    .useSiteTarget(AnnotationSpec.UseSiteTarget.FILE)
                    .addMember("%T::class", InternalAvro4kApi::class)
                    .addMember("%T::class", ExperimentalAvro4kApi::class)
                    .build()
            )
            .addTypes(listOf(this))
            .build()
    }
}

private data class SerializableTypeName(
    val typeName: TypeName,
    private val serializableAnnotation: AnnotationSpec?,
) {
    fun isNativelySerializable() = serializableAnnotation == null

    fun nullableIf(toBeNullable: Boolean): SerializableTypeName {
        if (toBeNullable == typeName.isNullable) return this
        return SerializableTypeName(
            typeName = typeName.copy(nullable = toBeNullable),
            serializableAnnotation = serializableAnnotation
        )
    }

    companion object {
        fun <T : Annotatable.Builder<T>> T.addSerializableAnnotation(type: SerializableTypeName): T {
            return if (type.serializableAnnotation != null) {
                addAnnotation(type.serializableAnnotation)
            } else {
                this
            }
        }
    }
}

private fun TypeName.nativelySerializable() = SerializableTypeName(this, serializableAnnotation = null)

private fun TypeName.contextual() = SerializableTypeName(this, serializableAnnotation = AnnotationSpec.builder(Contextual::class.asClassName()).build())

/**
 * Any non word character is considered as a separator, and the next character is capitalized.
 */
private fun String.toPascalCase(): String {
    return split(Regex("\\W")).joinToString("") { it.uppercaseFirstChar() }
}

private fun String.uppercaseFirstChar(): String {
    return replaceFirstChar { it.uppercaseChar() }
}

private fun TypeSafeSchema.NamedSchema.asClassName() = ClassName(space ?: "", name)

private fun parseJavaClassName(className: String): SerializableTypeName {
    return getKotlinClassReplacement(className)?.nativelySerializable() ?: ClassName.bestGuess(className).contextual()
}

/**
 * Returns this schema as a [TypeSafeSchema.UnionSchema] if it is a union, or if it is a collection (array or map) whose element/value schema is or contains a union.
 */
private fun TypeSafeSchema.nestedUnionToGenerateIfAny(): TypeSafeSchema.UnionSchema? {
    if (actualJavaClassName != null) return null
    return when (this) {
        is TypeSafeSchema.UnionSchema -> this
        is TypeSafeSchema.CollectionSchema.ArraySchema if actualElementClass == null -> elementSchema.nestedUnionToGenerateIfAny()
        is TypeSafeSchema.CollectionSchema.MapSchema -> valueSchema.nestedUnionToGenerateIfAny()
        else -> null
    }
}