package com.github.avrokotlin.avro4k.internal.decoder

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.NamedSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.internal.IllegalIndexedAccessError
import com.github.avrokotlin.avro4k.internal.schema.fullNameForMessages
import com.github.avrokotlin.avro4k.internal.schema.isUnion
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.SerializersModule

/**
 * Decodes a polymorphic value: finds the serial name of the subclass from the writer schema's name, then decodes the
 * value with [newDecoder].
 *
 * Public only for the generic data decoder of the `apache-interop` module.
 */
@InternalAvro4kApi
@OptIn(ExperimentalSerializationApi::class)
public abstract class AbstractPolymorphicDecoder(
    protected val avro: Avro,
    descriptor: SerialDescriptor,
    schema: AvroSchema,
) : AbstractDecoder() {
    final override val serializersModule: SerializersModule
        get() = avro.serializersModule

    /** Variables rather than values so that a decoder kept by its parent decodes each next polymorphic value: see [reset]. */
    private var descriptor: SerialDescriptor = descriptor
    private var schema: AvroSchema = schema

    /**
     * The branch of the writer schema the serial name was found for, and the schema its value is decoded with. Set on a
     * match only, so that among the branches of a union the first match is the one kept. Null until the serial name is
     * found.
     *
     * Recorded here rather than returned with the serial name, which would allocate a pair per decoded value.
     */
    protected var chosenSchema: AvroSchema? = null

    final override fun decodeString(): String {
        return findSerialName(descriptor, schema)
            ?: throw AvroSchemaMismatchException(
                "Unknown schema name '${schema.fullNameForMessages}' for polymorphic type ${descriptor.serialName}. Full schema: ${schema.toSchemaJsonString()}"
            )
    }

    /**
     * The serial name of the subclass of [descriptor] written with [schema], its branch recorded in [chosenSchema], or
     * null when no subclass matches. Open for a decoder that remembers the serial names it already found.
     */
    internal open fun findSerialName(
        descriptor: SerialDescriptor,
        schema: AvroSchema,
    ): String? {
        val namesAndAliasesToSerialName: Map<String, String> = avro.polymorphicResolver.getFullNamesAndAliasesToSerialName(descriptor)
        return tryFindSerialName(namesAndAliasesToSerialName, schema)
    }

    /** The serial name of the branch of the union [schema] the value is written with, recorded as [tryFindSerialName] does. */
    protected abstract fun tryFindSerialNameForUnion(
        namesAndAliasesToSerialName: Map<String, String>,
        schema: AvroSchema,
    ): String?

    /** The serial name matching [schema]'s full name or one of its aliases, [schema] then recorded in [chosenSchema]. */
    protected fun tryFindSerialName(
        namesAndAliasesToSerialName: Map<String, String>,
        schema: AvroSchema,
    ): String? {
        if (schema.isUnion) {
            return tryFindSerialNameForUnion(namesAndAliasesToSerialName, schema)
        }
        val serialName = namesAndAliasesToSerialName[schema.fullName] ?: schema.findSerialNameByAlias(namesAndAliasesToSerialName) ?: return null
        chosenSchema = schema
        return serialName
    }

    /** Only on a full-name miss: iterates the aliases of a named schema, without building the set of their full names. */
    private fun AvroSchema.findSerialNameByAlias(namesAndAliasesToSerialName: Map<String, String>): String? {
        if (this !is NamedSchema) return null
        for (alias in aliases) {
            namesAndAliasesToSerialName[alias.fullName]?.let { return it }
        }
        return null
    }

    final override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        return newDecoder(chosenSchema ?: throw noSerialNameDecodedError())
            .decodeSerializableValue(deserializer)
    }

    public abstract fun newDecoder(chosenSchema: AvroSchema): Decoder

    final override fun decodeSequentially(): Boolean = true

    final override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        throw IllegalIndexedAccessError()
    }

    /**
     * Makes this decoder decode a new polymorphic value, of [descriptor] written with [schema], as a new decoder would:
     * its serial name is not found yet.
     */
    internal open fun reset(
        descriptor: SerialDescriptor,
        schema: AvroSchema,
    ) {
        this.descriptor = descriptor
        this.schema = schema
        chosenSchema = null
    }
}

/**
 * What a polymorphic decoder throws when asked for the value before its serial name: a decoder kept for the next value
 * fails there as a new one does, instead of decoding with the branch its previous value was written with.
 */
private fun noSerialNameDecodedError(): IllegalStateException =
    IllegalStateException("No polymorphic value is being decoded: decodeString finds the subclass before its value is decoded")