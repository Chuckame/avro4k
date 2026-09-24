package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.AvroSchema.NullSchema
import com.github.avrokotlin.avro4k.AvroSchema.RecordSchema
import com.github.avrokotlin.avro4k.AvroSchema.UnionSchema
import com.github.avrokotlin.avro4k.internal.IdentitySet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.concurrent.Volatile

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

/**
 * This is a marker indicating that the schema is resolved, meaning it be of any type, except a [UnionSchema].
 * Any resolved schema can have properties on it.
 *
 * @see AvroSchema
 * @see WithProps
 */
public sealed class ResolvedSchema(
    type: Type,
    props: Map<String, JsonElement>,
) : AvroSchema(type, props.logicalTypeNameOrNull()), WithProps

/**
 * The `logicalType` prop as a string, or null when absent or not a JSON primitive (`JsonNull` included).
 * Never throws: a malformed `logicalType` is ignored, like Apache Avro ignores an invalid logical type.
 */
private fun Map<String, JsonElement>.logicalTypeNameOrNull(): String? = (this["logicalType"] as? JsonPrimitive)?.contentOrNull

/**
 * The multiplatform, pure-kotlin model of an Avro schema, the drop-in replacement of Apache Avro's `Schema` class.
 *
 * Equality is structural. It is cycle-safe for recursive records, but it walks the whole schema:
 * never use it on a hot path. Caches keyed by a schema must be identity-based.
 * A [RecordSchema]'s hash code only depends on its name, so hashing any schema is cheap and never recurses into records.
 */
public sealed class AvroSchema(
    /**
     * The kind of this schema. A stored field rather than an overridden getter, because encoders and decoders read it
     * once per value: a field read, like Apache Avro's `Schema.getType()`.
     */
    public val type: Type,
    /**
     * The name of this schema's logical type (the `logicalType` prop), or null when it has none.
     * Always null for a [UnionSchema]: a logical type annotates a single, resolved type.
     *
     * Computed once at construction, as the props are fixed by then, so reading it per value is a field read instead of
     * a map lookup plus a JSON cast. Like [type], it is derived from the constructor arguments, so it takes no part in
     * `equals`, `hashCode` or `toString`.
     */
    public val logicalTypeName: String?,
) {
    public open val fullName: String get() = simpleName
    public abstract val simpleName: String

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

    public sealed class PrimitiveSchema(
        type: Type,
        props: Map<String, JsonElement>,
    ) : ResolvedSchema(type, props)

    public data class BooleanSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema(Type.BOOLEAN, props) {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "boolean"
    }

    public data class IntSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema(Type.INT, props) {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "int"
    }

    public data class LongSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema(Type.LONG, props) {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "long"
    }

    public data class FloatSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema(Type.FLOAT, props) {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "float"
    }

    public data class DoubleSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema(Type.DOUBLE, props) {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "double"
    }

    public data class BytesSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema(Type.BYTES, props) {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "bytes"
    }

    public data class StringSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : PrimitiveSchema(Type.STRING, props) {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "string"
    }

    public data class NullSchema(
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : ResolvedSchema(Type.NULL, props) {
        init {
            ensureNotProhibitedProp("type")
        }

        override val simpleName: String
            get() = "null"
    }

    public data class UnionSchema(
        val types: List<ResolvedSchema>,
    ) : AvroSchema(Type.UNION, null) {
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
    ) : ResolvedSchema(Type.ARRAY, props) {
        init {
            ensureNotProhibitedProp("type", "items")
        }

        override val simpleName: String
            get() = "array"

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
    ) : ResolvedSchema(Type.MAP, props) {
        init {
            ensureNotProhibitedProp("type", "values")
        }

        override val simpleName: String
            get() = "map"

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

    public sealed class NamedSchema(
        type: Type,
        props: Map<String, JsonElement>,
    ) : ResolvedSchema(type, props), WithDoc {
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
    ) : NamedSchema(Type.RECORD, props) {
        /**
         * Field position by field name and by field alias, built on the first lookup rather than at construction:
         * a recursive record's [LockableList] is still empty when the constructor runs, so an index built there would
         * be empty. Kept out of the primary constructor, so it takes no part in the data class members, and ignored by
         * the hand-written [equals], [hashCode] and [toString].
         *
         * **Visibility, on every platform.** The map is fully built *before* the single write that publishes it, and
         * never mutated afterwards. The field is [Volatile]: on the JVM a volatile write happens-before every read that
         * observes it (JLS 17.4.5); on Kotlin/Native, [Volatile]'s contract is that a thread reading the value "sees
         * not only that value, but all side effects that led to writing that value"; JS is single-threaded. So a reader
         * that sees the map sees all of its entries, without relying on the JVM's final-field guarantee (which
         * Kotlin/Native does not have, and which a field written after construction would not get anyway).
         * Two threads may race on the first lookup and each build the map: both builds are equal and either one wins,
         * so the race is benign and no lock is needed.
         */
        @Volatile
        private var fieldIndexByName: Map<String, Int>? = null

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
            val names = HashSet<String>()
            for (field in fields) {
                require(names.add(field.name)) { "Record fields must be unique: '${field.name}' is used twice in record '$name'" }
                for (alias in field.aliases) {
                    require(names.add(alias)) { "Record fields must be unique: '$alias' is used twice in record '$name'" }
                }
            }
        }

        /**
         * Returns the position in [fields] of the field having the given name or alias, or null if there is none.
         *
         * O(1) after the first call on this instance, which builds the index. Throws [IllegalStateException] when
         * called on a record whose [LockableList] of fields is not locked yet.
         */
        public fun getFieldIndexOrNull(fieldName: String): Int? {
            return fieldIndex()[fieldName]
        }

        /**
         * Returns the field having the given name or alias, or null if there is none. Same cost as [getFieldIndexOrNull].
         */
        public fun getFieldOrNull(fieldName: String): Field? {
            val index = fieldIndex()[fieldName] ?: return null
            return fields[index]
        }

        /**
         * Returns the field having the given name or alias. Same cost as [getFieldIndexOrNull].
         *
         * @throws NoSuchElementException if there is no such field
         */
        public fun getFieldByName(fieldName: String): Field {
            return getFieldOrNull(fieldName) ?: throw NoSuchElementException("No field named '$fieldName' in record '$name'")
        }

        private fun fieldIndex(): Map<String, Int> {
            fieldIndexByName?.let { return it }
            check(fields !is LockableList || fields.isLocked) {
                "Record '$name' cannot be queried by field name before its LockableList of fields is locked"
            }
            val index = HashMap<String, Int>()
            for (i in fields.indices) {
                val field = fields[i]
                index[field.name] = i
                for (alias in field.aliases) {
                    index[alias] = i
                }
            }
            fieldIndexByName = index
            return index
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
        val size: Int,
        override val doc: String? = null,
        override val aliases: Set<Name> = emptySet(),
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : NamedSchema(Type.FIXED, props) {
        init {
            require(size >= 0) { "Fixed size must not be negative, but was $size for fixed '$name'" }
            require(name !in aliases) { "Fixed name '$name' cannot be part of aliases $aliases" }
            ensureNotProhibitedProp("type", "size", "name", "namespace", "aliases", "doc")
        }
    }

    public data class EnumSchema(
        override val name: Name,
        val symbols: List<String>,
        val defaultSymbol: String? = null,
        override val doc: String? = null,
        override val aliases: Set<Name> = emptySet(),
        override val props: Map<String, JsonElement> = emptyMap(),
    ) : NamedSchema(Type.ENUM, props) {
        /**
         * Symbol position by symbol, built once at construction ([symbols] is complete by then) so that encoding an enum
         * value is a hash lookup instead of an O(n) scan. It doubles as the uniqueness check, so it costs no extra work.
         * Kept out of the primary constructor, so it takes no part in the data class `equals`, `hashCode`,
         * `toString` or `copy`: it is derived from [symbols], so two equal enums always have equal indexes anyway.
         */
        private val symbolIndex: Map<String, Int> =
            HashMap<String, Int>().also { index ->
                for (i in symbols.indices) {
                    index[symbols[i]] = i
                }
            }

        init {
            require(name !in aliases) { "Enum name '$name' cannot be part of aliases $aliases" }
            require(symbolIndex.size == symbols.size) { "Enum symbols must be unique" }
            require(defaultSymbol == null || defaultSymbol in symbolIndex) { "Default symbol must be one of the enum symbols" }
            ensureNotProhibitedProp("type", "default", "symbols", "name", "namespace", "aliases", "doc")
        }

        /**
         * Returns the position of [symbol] in [symbols], or null if it is not one of them. O(1).
         */
        public fun getSymbolIndexOrNull(symbol: String): Int? {
            return symbolIndex[symbol]
        }
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