package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.nullable
import org.apache.avro.LogicalType
import org.apache.avro.Schema

internal class AvroUniqueSchemaGenerator : SchemaGenerator {
    private lateinit var generatedSchema: Schema

    private fun ensureSchemaNotGenerated() {
        if (this::generatedSchema.isInitialized) {
            throw IllegalStateException("Schema already generated")
        }
    }

    override fun scalar(type: ScalarSchemaType, builder: ScalarSchemaGenerator.() -> Unit) {
        ensureSchemaNotGenerated()
        generatedSchema = AvroScalarSchemaGenerator(type).apply(builder).build()
    }

    override fun array(builder: ArraySchemaGenerator.() -> Unit) {
        ensureSchemaNotGenerated()
        generatedSchema = AvroArraySchemaGenerator().apply(builder).build()
    }

    override fun map(builder: MapSchemaGenerator.() -> Unit) {
        ensureSchemaNotGenerated()
        generatedSchema = AvroMapSchemaGenerator().apply(builder).build()
    }

    override fun union(builder: SchemaGenerator.() -> Unit) {
        ensureSchemaNotGenerated()
        generatedSchema = AvroUnionSchemaGenerator().apply(builder).build()
    }

    override fun record(name: String, builder: RecordSchemaGenerator.() -> Unit) {
        ensureSchemaNotGenerated()
        generatedSchema = RecordSchemaGeneratorAvro(name).apply(builder).build()
    }

    override fun fixed(size: UInt, name: String, builder: FixedSchemaGenerator.() -> Unit) {
        ensureSchemaNotGenerated()
        generatedSchema = FixedSchemaGeneratorAvro(name, size).apply(builder).build()
    }

    override fun enum(name: String, builder: EnumSchemaGenerator.() -> Unit) {
        ensureSchemaNotGenerated()
        generatedSchema = EnumSchemaGeneratorAvro(name).apply(builder).build()
    }

    fun build(): Schema {
        return generatedSchema
    }
}

internal class AvroArraySchemaGenerator : ArraySchemaGenerator, AvroAnySchemaGenerator() {
    private lateinit var generatedItemSchema: Schema

    override fun items(builder: SchemaGenerator.() -> Unit) {
        if (this::generatedItemSchema.isInitialized) {
            throw IllegalStateException("Item schema already generated")
        }
        generatedItemSchema = AvroUniqueSchemaGenerator().apply(builder).build()
    }

    fun build(): Schema {
        return Schema.createArray(generatedItemSchema).also { applyProps(it) }.let { applyNullability(it) }
    }
}

internal class AvroMapSchemaGenerator : MapSchemaGenerator, AvroAnySchemaGenerator() {
    private lateinit var generatedItemSchema: Schema

    override fun values(builder: SchemaGenerator.() -> Unit) {
        if (this::generatedItemSchema.isInitialized) {
            throw IllegalStateException("Value schema already generated")
        }
        generatedItemSchema = AvroUniqueSchemaGenerator().apply(builder).build()
    }

    fun build(): Schema {
        return Schema.createMap(generatedItemSchema).also { applyProps(it) }.let { applyNullability(it) }
    }
}

internal class AvroScalarSchemaGenerator(private val type: ScalarSchemaType) : ScalarSchemaGenerator, AvroAnySchemaGenerator() {
    fun build(): Schema {
        return Schema.create(type.avroType).also { applyProps(it) }.let { applyNullability(it) }
    }

    private val ScalarSchemaType.avroType: Schema.Type
        get() = when (this) {
            ScalarSchemaType.STRING -> Schema.Type.STRING
            ScalarSchemaType.BYTES -> Schema.Type.BYTES
            ScalarSchemaType.INT -> Schema.Type.INT
            ScalarSchemaType.LONG -> Schema.Type.LONG
            ScalarSchemaType.FLOAT -> Schema.Type.FLOAT
            ScalarSchemaType.DOUBLE -> Schema.Type.DOUBLE
            ScalarSchemaType.BOOLEAN -> Schema.Type.BOOLEAN
        }
}

internal class AvroUnionSchemaGenerator : SchemaGenerator, AvroAnySchemaGenerator() {
    private val schemas = mutableListOf<Schema>()

    override fun record(name: String, builder: RecordSchemaGenerator.() -> Unit) {
        schemas.add(AvroUniqueSchemaGenerator().apply { record(name, builder) }.build())
    }

    override fun scalar(type: ScalarSchemaType, builder: ScalarSchemaGenerator.() -> Unit) {
        schemas.add(AvroUniqueSchemaGenerator().apply { scalar(type, builder) }.build())
    }

    override fun array(builder: ArraySchemaGenerator.() -> Unit) {
        schemas.add(AvroUniqueSchemaGenerator().apply { array(builder) }.build())
    }

    override fun map(builder: MapSchemaGenerator.() -> Unit) {
        schemas.add(AvroUniqueSchemaGenerator().apply { map(builder) }.build())
    }

    override fun fixed(size: UInt, name: String, builder: FixedSchemaGenerator.() -> Unit) {
        schemas.add(AvroUniqueSchemaGenerator().apply { fixed(size, name, builder) }.build())
    }

    override fun enum(name: String, builder: EnumSchemaGenerator.() -> Unit) {
        schemas.add(AvroUniqueSchemaGenerator().apply { enum(name, builder) }.build())
    }

    override fun union(builder: SchemaGenerator.() -> Unit) {
        // flatten unions
        builder(this)
    }

    fun build(): Schema {
        if (schemas.isEmpty()) {
            throw IllegalStateException("Union must have at least one schema")
        }
        return Schema.createUnion(schemas).also { applyProps(it) }.let { applyNullability(it) }
    }
}

internal abstract class AvroNamedSchemaGenerator : NamedSchemaGenerator, AvroAnySchemaGenerator() {
    protected var namespace: String? = null
    private var namespaceSet = false
    private lateinit var docValue: String
    private val aliases = mutableSetOf<String>()

    protected val doc: String get() = docValue

    override fun namespace(namespace: String) {
        if (namespaceSet) {
            throw IllegalStateException("Namespace already set to $namespace")
        }
        this.namespace = namespace
        namespaceSet = true
    }

    override fun removeNamespace() {
        if (namespaceSet) {
            throw IllegalStateException("Namespace already set to $namespace")
        }
        namespace = null
        namespaceSet = true
    }

    override fun doc(doc: String) {
        if (this::docValue.isInitialized) {
            throw IllegalStateException("Doc already set to $doc")
        }
        docValue = doc
    }

    override fun aliases(vararg aliases: String, sharedNamespace: String?) {
        if (sharedNamespace?.isNotEmpty() == true) {
            this.aliases.addAll(aliases.map { "$sharedNamespace.$it" })
        } else {
            this.aliases.addAll(aliases)
        }
    }

    override fun applyProps(schema: Schema) {
        super.applyProps(schema)
        aliases.forEach { schema.addAlias(it) }
    }
}

internal abstract class AvroAnySchemaGenerator : AnySchemaGenerator {
    private val props = mutableMapOf<String, String>()
    private var logicalType: LogicalType? = null
    private var nullable = false

    override fun nullable() {
        if (nullable) {
            throw IllegalStateException("Nullable already set")
        }
        nullable = true
    }

    override fun props(key: String, value: String) {
        props[key] = value
    }

    override fun props(vararg props: Pair<String, String>) {
        this.props.putAll(props)
    }

    override fun props(props: Map<String, String>) {
        this.props.putAll(props)
    }

    override fun logicalType(logicalType: LogicalType) {
        if (this.logicalType != null) {
            throw IllegalStateException("Logical type already set to ${this.logicalType}")
        }
        this.logicalType = logicalType
    }

    override fun logicalType(logicalTypeName: String) {
        if (this.logicalType != null) {
            throw IllegalStateException("Logical type already set to ${this.logicalType}")
        }
        this.logicalType = LogicalType(logicalTypeName)
    }

    protected open fun applyProps(schema: Schema) {
        props.forEach { (key, value) -> schema.addProp(key, value) }
        logicalType?.addToSchema(schema)
    }

    protected fun applyNullability(schema: Schema): Schema {
        if (nullable) {
            return schema.nullable
        }
        return schema
    }
}

internal class FixedSchemaGeneratorAvro(private val name: String, private val size: UInt) : FixedSchemaGenerator, AvroNamedSchemaGenerator() {
    fun build(): Schema {
        return Schema.createFixed(name, doc, namespace, size.toInt()).also { applyProps(it) }.let { applyNullability(it) }
    }
}

internal class RecordSchemaGeneratorAvro(private val name: String) : RecordSchemaGenerator, AvroNamedSchemaGenerator() {
    private val fields = mutableListOf<Schema.Field>()

    override fun field(name: String, builder: FieldSchemaGenerator.() -> Unit) {
        fields.add(FieldSchemaGeneratorAvro(name).apply(builder).build())
    }

    fun build(): Schema {
        return Schema.createRecord(name, doc, namespace, false, fields).apply { applyProps(this) }.let { applyNullability(it) }
    }
}

internal class FieldSchemaGeneratorAvro(private val name: String) : FieldSchemaGenerator {
    private lateinit var docValue: String
    private val aliases = mutableSetOf<String>()
    private var default: Any? = NO_DEFAULT_PLACEHOLDER
    private val props = mutableMapOf<String, String>()
    private lateinit var schema: Schema

    override fun doc(doc: String) {
        if (this::docValue.isInitialized) {
            throw IllegalStateException("Doc already set to $doc")
        }
        docValue = doc
    }

    override fun aliases(vararg aliases: String) {
        this.aliases.addAll(aliases)
    }

    override fun props(key: String, value: String) {
        props[key] = value
    }

    override fun props(vararg props: Pair<String, String>) {
        this.props.putAll(props)
    }

    override fun props(props: Map<String, String>) {
        this.props.putAll(props)
    }

    override fun defaultValue(value: Any?) {
        if (default !== NO_DEFAULT_PLACEHOLDER) {
            throw IllegalStateException("Default already set with $default")
        }
        default = value
    }

    override fun type(builder: SchemaGenerator.() -> Unit) {
        if (this::schema.isInitialized) {
            throw IllegalStateException("Type already set with $schema")
        }
        schema = AvroUniqueSchemaGenerator().apply(builder).build()
    }

    fun build(): Schema.Field {
        return Schema.Field(name, schema, docValue, if (default == null) Schema.Field.NULL_DEFAULT_VALUE else default.takeIf { it !== NO_DEFAULT_PLACEHOLDER })
            .also {
                aliases.forEach { alias -> it.addAlias(alias) }
                props.forEach { (k, v) -> it.addProp(k, v) }
            }
    }

    private companion object {
        private val NO_DEFAULT_PLACEHOLDER = Any()
    }
}

internal class EnumSchemaGeneratorAvro(private val name: String) : EnumSchemaGenerator, AvroNamedSchemaGenerator() {
    private val symbols = mutableSetOf<String>()
    private lateinit var defaultSymbolValue: String

    override fun defaultSymbol(name: String) {
        symbols.add(name)
        defaultSymbolValue = name
    }

    override fun symbol(name: String, isDefault: Boolean) {
        symbols.add(name)
        if (isDefault) {
            defaultSymbolValue = name
        }
    }

    override fun symbols(vararg symbols: String) {
        this.symbols.addAll(symbols)
    }

    fun build(): Schema {
        return Schema.createEnum(name, doc, namespace, symbols.toList(), if (this::defaultSymbolValue.isInitialized) defaultSymbolValue else null).apply { applyProps(this) }
            .let { applyNullability(it) }
    }
}
