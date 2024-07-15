package com.github.avrokotlin.avro4k

import org.apache.avro.LogicalType
import org.apache.avro.Schema

public sealed interface SchemaGenerator {
    public fun record(name: String, builder: RecordSchemaGenerator.() -> Unit)
    public fun enum(name: String, builder: EnumSchemaGenerator.() -> Unit)
    public fun scalar(type: ScalarSchemaType, builder: ScalarSchemaGenerator.() -> Unit = {})
    public fun array(builder: ArraySchemaGenerator.() -> Unit)
    public fun map(builder: MapSchemaGenerator.() -> Unit)
    public fun fixed(size: UInt, name: String, builder: FixedSchemaGenerator.() -> Unit = {})
    public fun union(builder: SchemaGenerator.() -> Unit)

    public companion object {
        public fun record(name: String, builder: RecordSchemaGenerator.() -> Unit): Schema {
            return AvroUniqueSchemaGenerator().apply { record(name, builder) }.build()
        }

        public fun enum(name: String, builder: EnumSchemaGenerator.() -> Unit): Schema {
            return AvroUniqueSchemaGenerator().apply { enum(name, builder) }.build()
        }

        public fun scalar(type: ScalarSchemaType, builder: ScalarSchemaGenerator.() -> Unit = {}): Schema {
            return AvroUniqueSchemaGenerator().apply { scalar(type, builder) }.build()
        }

        public fun array(builder: ArraySchemaGenerator.() -> Unit): Schema {
            return AvroUniqueSchemaGenerator().apply { array(builder) }.build()
        }

        public fun map(builder: MapSchemaGenerator.() -> Unit): Schema {
            return AvroUniqueSchemaGenerator().apply { map(builder) }.build()
        }

        public fun fixed(size: UInt, name: String, builder: FixedSchemaGenerator.() -> Unit = {}): Schema {
            return AvroUniqueSchemaGenerator().apply { fixed(size, name, builder) }.build()
        }

        public fun union(builder: SchemaGenerator.() -> Unit): Schema {
            return AvroUniqueSchemaGenerator().apply { union(builder) }.build()
        }
    }
}

public sealed interface PropsSchemaGenerator {
    public fun props(key: String, value: String)
    public fun props(vararg props: Pair<String, String>)
    public fun props(props: Map<String, String>)
}

public sealed interface DocSchemaGenerator {
    public fun doc(doc: String)
}

public sealed interface AnySchemaGenerator : PropsSchemaGenerator {
    public fun nullable()
    public fun logicalType(logicalType: LogicalType)
    public fun logicalType(logicalTypeName: String)
}

public sealed interface NamedSchemaGenerator : AnySchemaGenerator, DocSchemaGenerator {
    public fun namespace(namespace: String)
    public fun removeNamespace()
    public fun aliases(vararg aliases: String, sharedNamespace: String? = null)
}

public enum class ScalarSchemaType {
    BOOLEAN,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    STRING,
    BYTES,
}

public sealed interface RecordSchemaGenerator : NamedSchemaGenerator {
    public fun field(name: String, builder: FieldSchemaGenerator.() -> Unit)
}

public sealed interface FieldSchemaGenerator : PropsSchemaGenerator, DocSchemaGenerator {
    public fun aliases(vararg aliases: String)
    public fun defaultValue(value: Any?)
    public fun type(builder: SchemaGenerator.() -> Unit)
}

public sealed interface EnumSchemaGenerator : NamedSchemaGenerator {
    public fun defaultSymbol(name: String)
    public fun symbol(name: String, isDefault: Boolean = false)
    public fun symbols(vararg symbols: String)
}

public sealed interface ScalarSchemaGenerator : AnySchemaGenerator

public sealed interface ArraySchemaGenerator : AnySchemaGenerator {
    public fun items(builder: SchemaGenerator.() -> Unit)
}

public sealed interface MapSchemaGenerator : AnySchemaGenerator {
    public fun values(builder: SchemaGenerator.() -> Unit)
}

public sealed interface FixedSchemaGenerator : NamedSchemaGenerator
