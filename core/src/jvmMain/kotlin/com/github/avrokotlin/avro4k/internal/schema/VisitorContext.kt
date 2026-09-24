package com.github.avrokotlin.avro4k.internal.schema

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroAlias
import com.github.avrokotlin.avro4k.AvroConfiguration
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroDoc
import com.github.avrokotlin.avro4k.AvroProp
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.NamedSchema
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.Name
import com.github.avrokotlin.avro4k.internal.AvroSchemaGenerationException
import com.github.avrokotlin.avro4k.internal.findAnnotation
import com.github.avrokotlin.avro4k.internal.findAnnotations
import com.github.avrokotlin.avro4k.internal.findElementAnnotation
import com.github.avrokotlin.avro4k.internal.findElementAnnotations
import com.github.avrokotlin.avro4k.internal.jsonElement
import com.github.avrokotlin.avro4k.serializer.ElementLocation
import com.github.avrokotlin.avro4k.serializer.SchemaSupplierContext
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonElement

internal data class VisitorContext(
    val avro: Avro,
    /**
     * The named schemas of the schema being generated, by full name, so that each named type has **one** instance: a
     * record is registered before its fields are visited (recursive records), and the other named schemas (enums, and the
     * ones of the custom serializers, like fixed) when built ([shareNamedSchema]).
     */
    val resolvedSchemas: MutableMap<String, NamedSchema>,
    override val inlinedElements: List<ElementLocation> = emptyList(),
) : SchemaSupplierContext {
    override val configuration: AvroConfiguration
        get() = avro.configuration

    /**
     * The one instance of this named schema in the schema being generated: the first one built with its full name, or
     * this one. A union's named branches are shared the same way.
     *
     * Why: a name defines one type in Avro, and the json of a schema defines each named type once, then refers to it by
     * name. The generation used to build an enum or a fixed anew at each use, equal instances that the json route of
     * `Avro.schema()` could not pair one by one with the single Apache node its parser shares. Two *different*
     * named schemas with the same full name cannot be written as json at all: the second one would silently read as the
     * first (e.g. a fixed of another size), so it is rejected, with a message that starts like Apache's `Schema.Parser` one
     * (`Can't redefine: <full name>`).
     *
     * Cold path (once per generated schema): the structural `==` below only runs when a full name is met again with
     * another instance, and it is what tells an equal duplicate from a conflict.
     */
    fun shareNamedSchema(schema: AvroSchema): AvroSchema {
        return when (schema) {
            is NamedSchema -> shareNamedSchema(schema)

            is UnionSchema -> {
                val types = schema.types
                val shared = types.map { if (it is NamedSchema) shareNamedSchema(it) else it }
                if (types.indices.all { types[it] === shared[it] }) schema else UnionSchema(shared)
            }

            else -> schema
        }
    }

    private fun shareNamedSchema(schema: NamedSchema): NamedSchema {
        val existing = resolvedSchemas.getOrPut(schema.fullName) { schema }
        if (existing === schema || existing.isEqualTo(schema)) return existing
        throw AvroSchemaGenerationException(
            "Can't redefine: ${schema.fullName}. Two different named schemas have this full name: ${existing.toSchemaJsonStringOrName()} and " +
                "${schema.toSchemaJsonStringOrName()}. A name can only define one type in a schema."
        )
    }

    /** Structural equality, false for a record still being built (its fields cannot be read before being locked). */
    private fun NamedSchema.isEqualTo(other: NamedSchema): Boolean =
        try {
            this == other
        } catch (_: IllegalStateException) {
            false
        }
}

/**
 * Contains all the annotations for a field of a class (kind == CLASS && isInline == true).
 */
internal data class InlineClassFieldAnnotations(
    val props: Sequence<AvroProp>,
) {
    constructor(inlineClassDescriptor: SerialDescriptor) : this(
        sequence {
            yieldAll(inlineClassDescriptor.findAnnotations<AvroProp>())
            yieldAll(inlineClassDescriptor.findElementAnnotations<AvroProp>(0))
        }
    ) {
        require(inlineClassDescriptor.isInline) {
            "${InlineClassFieldAnnotations::class.qualifiedName} is only for inline classes, but trying to use it with non-inline class descriptor $inlineClassDescriptor"
        }
    }
}

/**
 * Contains all the annotations for a field of a class (kind == CLASS && isInline == false).
 */
internal data class FieldAnnotations(
    val props: Sequence<AvroProp>,
    val aliases: AvroAlias?,
    val doc: AvroDoc?,
    val default: AvroDefault?,
) {
    constructor(descriptor: SerialDescriptor, elementIndex: Int) : this(
        descriptor.findElementAnnotations<AvroProp>(elementIndex).asSequence(),
        descriptor.findElementAnnotation<AvroAlias>(elementIndex),
        descriptor.findElementAnnotation<AvroDoc>(elementIndex),
        descriptor.findElementAnnotation<AvroDefault>(elementIndex)
    ) {
        require(descriptor.kind == StructureKind.CLASS) {
            "${FieldAnnotations::class.qualifiedName} is only for classes, but trying at element index $elementIndex with non class descriptor $descriptor"
        }
    }
}

/**
 * Contains all the annotations for a class, object or enum (kind == CLASS || kind == OBJECT || kind == ENUM).
 */
internal data class TypeAnnotations(
    val props: Sequence<AvroProp>,
    val aliases: AvroAlias?,
    val doc: AvroDoc?,
) {
    constructor(descriptor: SerialDescriptor) : this(
        descriptor.findAnnotations<AvroProp>().asSequence(),
        descriptor.findAnnotation<AvroAlias>(),
        descriptor.findAnnotation<AvroDoc>()
    ) {
        require(descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT || descriptor.kind == SerialKind.ENUM) {
            "TypeAnnotations are only for classes, objects and enums. Actual: $descriptor"
        }
    }
}

// ---- Names and props, built the way Apache's `Schema` builders did ----
// The visitors used to build Apache schemas with `Schema.createRecord`, `addProp`, `addAlias`, `Schema.Field`…, which
// validate names and props. The model does not, or not the same way, so these helpers keep Apache's checks and messages.
// Only the exception class changes: an AvroSchemaGenerationException (a SerializationException) instead of Apache's
// SchemaParseException / AvroRuntimeException.

/** The props reserved by Apache's `Schema` (`SCHEMA_RESERVED`): its `addProp` rejects them. */
internal val SCHEMA_RESERVED_PROPS: Set<String> = setOf("doc", "fields", "items", "name", "namespace", "size", "symbols", "values", "type", "aliases")

/** The props reserved by Apache's enum `Schema` (`ENUM_RESERVED`). */
internal val ENUM_RESERVED_PROPS: Set<String> = SCHEMA_RESERVED_PROPS + "default"

/** The props reserved by Apache's `Schema.Field` (`FIELD_RESERVED`). */
internal val FIELD_RESERVED_PROPS: Set<String> = setOf("default", "doc", "name", "order", "type", "aliases")

/**
 * The props of these annotations, in their order, added one by one like Apache's `JsonProperties.addProp`: a reserved
 * name is rejected, the same prop added twice with the same value is kept once (the first one), and with another value is
 * rejected.
 */
internal fun Sequence<AvroProp>.toSchemaProps(reserved: Set<String>): Map<String, JsonElement> {
    val props = LinkedHashMap<String, JsonElement>()
    forEach { props.addProp(it.key, it.jsonElement, reserved) }
    return props
}

/** Apache's `JsonProperties.addProp`, on the model's props. */
internal fun MutableMap<String, JsonElement>.addProp(
    name: String,
    value: JsonElement,
    reserved: Set<String>,
) {
    if (name in reserved) {
        throw AvroSchemaGenerationException("Can't set reserved property: $name")
    }
    val old = putIfAbsent(name, value)
    if (old != null && old != value) {
        throw AvroSchemaGenerationException("Can't overwrite property: $name")
    }
}

/**
 * The name of a record or an enum, validated like Apache's `Schema.Name`: only the simple name (after the last dot) is
 * checked, not the namespace.
 */
internal fun namedSchemaName(fullName: String): Name {
    return Name(fullName).also { validateAvroName(it.simpleName) }
}

/** The aliases of a record or an enum, relative to its namespace like Apache's `Schema.addAlias`, and validated like it. */
internal fun AvroAlias?.toSchemaAliases(schemaName: Name): Set<Name> {
    val aliases = this?.value ?: return emptySet()
    return aliases.mapTo(LinkedHashSet(aliases.size)) { alias -> Name(alias, schemaName.space).also { validateAvroName(it.simpleName) } }
}

/**
 * Apache's default name validation (`NameValidator.UTF_VALIDATOR`), applied by its `Schema.Name`, enum symbol and
 * `Schema.Field` constructors: a letter or `_` first, then letters, digits or `_`.
 */
internal fun validateAvroName(name: String): String {
    if (name.isEmpty()) {
        throw AvroSchemaGenerationException("Empty name")
    }
    val first = name[0]
    if (!(first.isLetter() || first == '_')) {
        throw AvroSchemaGenerationException("Illegal initial character: $name")
    }
    for (i in 1 until name.length) {
        val c = name[i]
        if (!(c.isLetterOrDigit() || c == '_')) {
            throw AvroSchemaGenerationException("Illegal character in: $name")
        }
    }
    return name
}