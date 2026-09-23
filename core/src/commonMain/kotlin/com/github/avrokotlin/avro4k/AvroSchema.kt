package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.AvroSchema.NullSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.internal.IdentitySet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@InternalAvro4kApi
public interface WithProps {
    public val props: Map<String, JsonElement>
}

@InternalAvro4kApi
public interface WithDoc {
    public val doc: String?
}

public val AvroSchema.isNullable: Boolean get() = this is NullSchema || (this is UnionSchema && isNullable)
public val AvroSchema.nullable: AvroSchema
    get() {
        if (isNullable) return this
        return if (this is UnionSchema) {
            UnionSchema(listOf(NullSchema()) + this.types)
        } else {
            UnionSchema(NullSchema(), this as ResolvedSchema)
        }
    }

public val ResolvedSchema.logicalTypeName: String?
    get() = props["logicalType"]?.jsonPrimitive?.contentOrNull

/**
 * This is a marker indicating that the schema is resolved, meaning it be of any type, except a [UnionSchema].
 * Any resolved schema can have properties on it.
 *
 * @see AvroSchema
 * @see WithProps
 */
public sealed class ResolvedSchema : AvroSchema(), WithProps

/**
 * The multiplatform, pure-kotlin model of an Avro schema, the drop-in replacement of Apache Avro's `Schema` class.
 *
 * Equality is structural. It is cycle-safe for recursive records, but it walks the whole schema:
 * never use it on a hot path. Caches keyed by a schema must be identity-based.
 * A [RecordSchema]'s hash code only depends on its name, so hashing any schema is cheap and never recurses into records.
 */
public sealed class AvroSchema {
    public open val fullName: String get() = simpleName
    public abstract val simpleName: String
    public abstract val type: Type

    internal open fun equals(
        other: Any?,
        seen: MutableSet<SeenPair>,
    ): Boolean {
        return equals(other)
    }

    internal open fun toString(seen: IdentitySet<RecordSchema>): String {
        return toString()
    }

    public enum class Type {
        RECORD,
        ENUM,
        FIXED,
        ARRAY,
        MAP,
        UNION,
        NULL,
        BOOLEAN,
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        BYTES,
        STRING,
    }

    public sealed class PrimitiveSchema : ResolvedSchema()

    public data class BooleanSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema() {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "boolean"
        override val type: Type
            get() = Type.BOOLEAN
    }

    public data class IntSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema() {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "int"
        override val type: Type
            get() = Type.INT
    }

    public data class LongSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema() {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "long"
        override val type: Type
            get() = Type.LONG
    }

    public data class FloatSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema() {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "float"
        override val type: Type
            get() = Type.FLOAT
    }

    public data class DoubleSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema() {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "double"
        override val type: Type
            get() = Type.DOUBLE
    }

    public data class BytesSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema() {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "bytes"
        override val type: Type
            get() = Type.BYTES
    }

    public data class StringSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema() {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "string"
        override val type: Type
            get() = Type.STRING
    }

    public data class NullSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : ResolvedSchema() {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "null"
        override val type: Type
            get() = Type.NULL
    }

    public data class UnionSchema(
        val types: List<ResolvedSchema>,
    ) : AvroSchema() {
        val isNullable: Boolean = types.any { it is NullSchema }
        val isSimpleNullableType: Boolean get() = types.size == 2 && isNullable

        private val typesIndexByFullName: Map<String, Int> =
            buildMap(types.size) {
                iterateOverFullNamesIncludingAliases { fullName, index, _ -> put(fullName, index) }
            }

        public fun getIndexOrNull(fullName: String): Int? = typesIndexByFullName[fullName]

        public constructor(vararg types: ResolvedSchema) : this(types.toList())

        init {
            require(types.isNotEmpty()) { "Union must have at least one type" }
            require(types.associateBy { it.fullName }.size == types.size) { "Union cannot contain duplicate type full-names" }

            val similarTypeNames =
                buildMap(types.size) {
                    iterateOverFullNamesIncludingAliases { fullName, _, _ ->
                        val count = getOrPut(fullName) { 0 }
                        put(fullName, count + 1)
                    }
                }.filterValues { it > 1 }.keys
            require(similarTypeNames.isEmpty()) { "Similar type names or aliases found: $similarTypeNames" }
        }

        override val simpleName: String
            get() = "union"
        override val type: Type
            get() = Type.UNION

        override fun equals(other: Any?): Boolean {
            return equals(other, HashSet())
        }

        override fun equals(
            other: Any?,
            seen: MutableSet<SeenPair>,
        ): Boolean {
            if (this === other) return true
            if (other !is UnionSchema) return false
            if (types.size != other.types.size) return false
            for (i in types.indices) {
                if (!types[i].equals(other.types[i], seen)) return false
            }
            return true
        }

        override fun hashCode(): Int {
            return types.hashCode()
        }

        override fun toString(): String {
            return toString(IdentitySet())
        }

        override fun toString(seen: IdentitySet<RecordSchema>): String {
            return "${UnionSchema::class.simpleName}(types=[${types.joinToString { it.toString(seen) }}])"
        }

        private inline fun iterateOverFullNamesIncludingAliases(crossinline onFullName: (fullName: String, index: Int, type: ResolvedSchema) -> Unit) {
            for (index in types.indices) {
                val type = types[index]
                if (type is NamedSchema) {
                    for (alias in type.aliases) {
                        onFullName(alias.fullName, index, type)
                    }
                }
                onFullName(type.fullName, index, type)
            }
        }
    }

    public data class ArraySchema(
        val elementSchema: AvroSchema,
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : ResolvedSchema() {
        init {
            ensureNotProhibitedProp("type", "items")
        }

        override val simpleName: String
            get() = "array"
        override val type: Type
            get() = Type.ARRAY

        override fun equals(other: Any?): Boolean {
            return equals(other, HashSet())
        }

        override fun equals(
            other: Any?,
            seen: MutableSet<SeenPair>,
        ): Boolean {
            if (this === other) return true
            if (other !is ArraySchema) return false
            if (props != other.props) return false
            return elementSchema.equals(other.elementSchema, seen)
        }

        override fun hashCode(): Int {
            return 31 * props.hashCode() + elementSchema.hashCode()
        }

        override fun toString(): String {
            return toString(IdentitySet())
        }

        override fun toString(seen: IdentitySet<RecordSchema>): String {
            return "${ArraySchema::class.simpleName}(props=$props, elementSchema=${elementSchema.toString(seen)})"
        }
    }

    public data class MapSchema(
        val valueSchema: AvroSchema,
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : ResolvedSchema() {
        init {
            ensureNotProhibitedProp("type", "values")
        }

        override val simpleName: String
            get() = "map"
        override val type: Type
            get() = Type.MAP

        override fun equals(other: Any?): Boolean {
            return equals(other, HashSet())
        }

        override fun equals(
            other: Any?,
            seen: MutableSet<SeenPair>,
        ): Boolean {
            if (this === other) return true
            if (other !is MapSchema) return false
            if (props != other.props) return false
            return valueSchema.equals(other.valueSchema, seen)
        }

        override fun hashCode(): Int {
            return 31 * props.hashCode() + valueSchema.hashCode()
        }

        override fun toString(): String {
            return toString(IdentitySet())
        }

        override fun toString(seen: IdentitySet<RecordSchema>): String {
            return "${MapSchema::class.simpleName}(props=$props, valueSchema=${valueSchema.toString(seen)})"
        }
    }

    public sealed class NamedSchema : ResolvedSchema(), WithDoc {
        public abstract val name: Name
        public abstract val aliases: Set<Name>

        override val fullName: String
            get() = name.fullName
        override val simpleName: String
            get() = name.simpleName
    }

    /**
     * To build a recursive record, pass a [LockableList] as [fields], and lock it once all the fields have been added:
     * the fields are validated at that moment, and the list cannot be read before.
     */
    public data class RecordSchema(
        override val name: Name,
        val fields: List<Field>,
        override val doc: String? = null,
        override val aliases: Set<Name> = emptySet(),
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : NamedSchema() {
        private lateinit var fieldsByName: Map<String, Field>

        init {
            require(name !in aliases) { "Record name '$name' cannot be part of aliases $aliases" }
            ensureNotProhibitedProp("type", "fields", "name", "namespace", "aliases", "doc")
            if (fields is LockableList && !fields.isLocked) {
                fields.onLock = ::validateFields
            } else {
                validateFields(fields)
            }
        }

        private fun validateFields(fields: List<Field>) {
            fieldsByName = fields.flatMap { f -> listOf(f.name to f) + f.aliases.map { it to f } }.toMap()
            require(fieldsByName.size == fields.size + fields.sumOf { it.aliases.size }) { "Record fields must be unique" }
        }

        override val type: Type get() = Type.RECORD

        /**
         * Returns the field having the given name or alias.
         */
        public fun getFieldByName(fieldName: String): Field {
            return fieldsByName.getValue(fieldName)
        }

        override fun equals(other: Any?): Boolean {
            return equals(other, HashSet())
        }

        override fun equals(
            other: Any?,
            seen: MutableSet<SeenPair>,
        ): Boolean {
            if (this === other) return true
            if (other !is RecordSchema) return false
            if (name != other.name) return false
            if (props != other.props) return false
            if (fields.size != other.fields.size) return false

            val pair = SeenPair(this, other)
            // Already comparing this pair higher in the stack: assume equal, the other fields decide (co-inductive equality)
            if (!seen.add(pair)) return true
            for (i in fields.indices) {
                if (!fields[i].equals(other.fields[i], seen)) return false
            }
            seen.remove(pair)
            return true
        }

        /**
         * Only depends on the name, which is consistent with [equals] as equal records always have the same name.
         * This keeps hashing O(1): it never walks the fields, so it is cycle-free, allocation-free,
         * and stays valid even while the fields list is still being populated.
         */
        override fun hashCode(): Int {
            return name.hashCode()
        }

        override fun toString(): String {
            return toString(IdentitySet())
        }

        override fun toString(seen: IdentitySet<RecordSchema>): String {
            if (!seen.add(this)) return "${RecordSchema::class.simpleName}(name=$name)"
            return "${RecordSchema::class.simpleName}(name=$name, doc=$doc, aliases=$aliases, props=$props, fields=${fields.joinToString { it.toString(seen) }})"
        }

        public data class Field(
            val name: String,
            val schema: AvroSchema,
            val defaultValue: JsonElement? = null,
            override val doc: String? = null,
            val aliases: Set<String> = emptySet(),
            override val props: Map<String, JsonElement> = emptyMap(),
        ) : WithProps, WithDoc {
            init {
                require(name !in aliases) { "Field name '$name' cannot be part of aliases $aliases" }
                ensureNotProhibitedProp("name", "type", "aliases", "doc", "default")
                if (defaultValue != null) {
                    require(isValidDefault(defaultValue, schema)) { "'$defaultValue' is not a compatible default value for field '$name' with schema $schema" }
                }
            }

            internal fun equals(
                other: Field,
                seen: MutableSet<SeenPair>,
            ): Boolean {
                if (this === other) return true
                if (name != other.name) return false
                if (defaultValue != other.defaultValue) return false
                if (props != other.props) return false

                return schema.equals(other.schema, seen)
            }

            internal fun toString(seen: IdentitySet<RecordSchema>): String {
                return "${Field::class.simpleName}(name=$name, defaultValue=$defaultValue, doc=$doc, aliases=$aliases, props=$props, schema=${schema.toString(seen)})"
            }

            override fun toString(): String {
                return toString(IdentitySet())
            }

            private fun isValidDefault(
                defaultValue: JsonElement,
                schema: AvroSchema,
            ): Boolean {
                if (schema is UnionSchema) {
                    // A default value must be of type of the first one in a union
                    return defaultValue.isValidJsonForSchema(schema.types[0])
                }
                return defaultValue.isValidJsonForSchema(schema)
            }
        }
    }

    public data class FixedSchema(
        override val name: Name,
        val size: UInt,
        override val doc: String? = null,
        override val aliases: Set<Name> = emptySet(),
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : NamedSchema() {
        init {
            require(name !in aliases) { "Fixed name '$name' cannot be part of aliases $aliases" }
            ensureNotProhibitedProp("type", "size", "name", "namespace", "aliases", "doc")
        }

        override val type: Type get() = Type.FIXED
    }

    public data class EnumSchema(
        override val name: Name,
        val symbols: List<String>,
        val defaultSymbol: String? = null,
        override val doc: String? = null,
        override val aliases: Set<Name> = emptySet(),
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : NamedSchema() {
        init {
            require(name !in aliases) { "Enum name '$name' cannot be part of aliases $aliases" }
            require(symbols.toSet().size == symbols.size) { "Enum symbols must be unique" }
            require(defaultSymbol == null || defaultSymbol in symbols) { "Default symbol must be one of the enum symbols" }
            ensureNotProhibitedProp("type", "default", "symbols", "name", "namespace", "aliases", "doc")
        }

        override val type: Type get() = Type.ENUM
    }

    public companion object {
        // placeholder to allow static extensions to be added like AvroSchema.fromJsonString()
    }
}

/**
 * The name of a named schema. When [name] contains a dot, its namespace is taken from it and [space] is ignored.
 * An empty namespace is the same as no namespace.
 */
public class Name {
    public val fullName: String
    public val simpleName: String
    public val space: String?

    public constructor(name: String, space: String? = null) {
        val dot = name.lastIndexOf('.')
        if (dot != -1) {
            this.simpleName = name.substring(dot + 1)
            this.space = name.substring(0, dot).ifEmpty { space }?.ifEmpty { null }
        } else {
            this.simpleName = name
            this.space = space?.ifEmpty { null }
        }
        this.fullName = if (this.space == null) simpleName else "${this.space}.$simpleName"
    }

    override fun toString(): String {
        return fullName
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Name) return false
        return fullName == other.fullName
    }

    override fun hashCode(): Int {
        return fullName.hashCode()
    }
}

private fun WithProps.ensureNotProhibitedProp(vararg prohibited: String) {
    for (p in prohibited) {
        require(p !in props) { "properties in ${this::class.simpleName} must not contain the field '$p'. Actual value: ${props[p]}" }
    }
}

/**
 * A pair of records being compared, compared by identity. Its hash code is the records' one, which is O(1) (name-based).
 */
internal class SeenPair(
    val first: RecordSchema,
    val second: RecordSchema,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SeenPair) return false
        return first === other.first && second === other.second
    }

    override fun hashCode(): Int {
        return 31 * first.hashCode() + second.hashCode()
    }
}

/**
 * A list that can be filled, then locked. It cannot be read before being locked, nor modified after.
 * Used to build recursive records: create the record with an empty [LockableList], register the record
 * so its fields can reference it, add the fields, then [lock] the list.
 */
@InternalAvro4kApi
public class LockableList<T> : AbstractMutableList<T>() {
    private val list = mutableListOf<T>()
    public var isLocked: Boolean = false
        private set
    internal var onLock: ((List<T>) -> Unit)? = null

    public fun lock() {
        ensureNotLocked()
        isLocked = true
        onLock?.invoke(this)
        onLock = null
    }

    override fun set(
        index: Int,
        element: T,
    ): T {
        ensureNotLocked()
        return list.set(index, element)
    }

    override fun add(
        index: Int,
        element: T,
    ) {
        ensureNotLocked()
        list.add(index, element)
    }

    // AbstractMutableList.add(element) would read `size`, which is not allowed before locking
    override fun add(element: T): Boolean {
        ensureNotLocked()
        return list.add(element)
    }

    override fun removeAt(index: Int): T {
        ensureNotLocked()
        return list.removeAt(index)
    }

    override val size: Int
        get() {
            ensureLocked()
            return list.size
        }

    override fun get(index: Int): T {
        ensureLocked()
        return list[index]
    }

    private fun ensureNotLocked() {
        check(!isLocked) { "Cannot modify a locked list" }
    }

    private fun ensureLocked() {
        check(isLocked) { "Cannot access a non-locked list" }
    }
}