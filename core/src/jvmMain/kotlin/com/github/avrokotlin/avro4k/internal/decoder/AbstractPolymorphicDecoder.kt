package com.github.avrokotlin.avro4k.internal.decoder

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.NamedSchema
import com.github.avrokotlin.avro4k.internal.IllegalIndexedAccessError
import com.github.avrokotlin.avro4k.internal.schema.fullNameForMessages
import com.github.avrokotlin.avro4k.internal.schema.isUnion
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.AbstractDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.modules.SerializersModule

@OptIn(ExperimentalSerializationApi::class)
internal abstract class AbstractPolymorphicDecoder(
    protected val avro: Avro,
    private val descriptor: SerialDescriptor,
    private val schema: AvroSchema,
) : AbstractDecoder() {
    final override val serializersModule: SerializersModule
        get() = avro.serializersModule

    private lateinit var chosenSchema: AvroSchema

    final override fun decodeString(): String {
        return tryFindSerialName()?.also { chosenSchema = it.second }?.first
            ?: throw SerializationException(
                "Unknown schema name '${schema.fullNameForMessages}' for polymorphic type ${descriptor.serialName}. Full schema: ${schema.toSchemaJsonString()}"
            )
    }

    private fun tryFindSerialName(): Pair<String, AvroSchema>? {
        val namesAndAliasesToSerialName: Map<String, String> = avro.polymorphicResolver.getFullNamesAndAliasesToSerialName(descriptor)
        return tryFindSerialName(namesAndAliasesToSerialName, schema)
    }

    protected abstract fun tryFindSerialNameForUnion(
        namesAndAliasesToSerialName: Map<String, String>,
        schema: AvroSchema,
    ): Pair<String, AvroSchema>?

    protected fun tryFindSerialName(
        namesAndAliasesToSerialName: Map<String, String>,
        schema: AvroSchema,
    ): Pair<String, AvroSchema>? {
        if (schema.isUnion) {
            return tryFindSerialNameForUnion(namesAndAliasesToSerialName, schema)
        }
        val serialName = namesAndAliasesToSerialName[schema.fullName] ?: schema.findSerialNameByAlias(namesAndAliasesToSerialName)
        return serialName?.let { it to schema }
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
        return newDecoder(chosenSchema)
            .decodeSerializableValue(deserializer)
    }

    abstract fun newDecoder(chosenSchema: AvroSchema): Decoder

    final override fun decodeSequentially() = true

    final override fun decodeElementIndex(descriptor: SerialDescriptor): Int {
        throw IllegalIndexedAccessError()
    }
}