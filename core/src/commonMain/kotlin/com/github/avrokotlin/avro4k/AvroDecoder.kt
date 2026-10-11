package com.github.avrokotlin.avro4k

import com.github.avrokotlin.avro4k.internal.decoder.direct.AbstractAvroDirectDecoder
import com.github.avrokotlin.avro4k.internal.schema.fullNameForMessages
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import kotlinx.serialization.encoding.Decoder

/**
 * Interface to decode Avro values.
 * Here are the main methods to decode values. Each decode method is adapting the raw type to the wanted type, that means unions are resolved if needed, and also all primitives are converted automatically (a wanted `long` could be decoded from a `int`).
 *
 * Primitives:
 * - [decodeNull]
 * - [decodeBoolean]
 * - [decodeByte]
 * - [decodeShort]
 * - [decodeInt]
 * - [decodeLong]
 * - [decodeFloat]
 * - [decodeDouble]
 * - [decodeString]
 * - [decodeChar]
 * - [decodeEnum]
 *
 * Avro specific:
 * - [decodeBytes]
 * - [decodeFixed]
 *
 * To decode a value differently depending on its writer schema (mainly for logical types), read [currentWriterSchema],
 * which is never a union, then call the matching typed method:
 * ```kotlin
 * val schema = decoder.currentWriterSchema
 * when (schema.type) {
 *     AvroSchema.Type.LONG -> Instant.ofEpochMilli(decoder.decodeLong())
 *     AvroSchema.Type.STRING -> Instant.parse(decoder.decodeString())
 *     else -> throw AvroSchemaMismatchException("Unsupported writer schema $schema")
 * }
 * ```
 *
 * To decode a value as whatever type its [currentWriterSchema] has, without knowing it in advance, use
 * `decodeSerializableValue(AnySerializer())` ([com.github.avrokotlin.avro4k.serializer.AnySerializer]), which also decodes
 * logical types as configured in [AvroConfiguration.logicalTypes].
 */
public interface AvroDecoder : Decoder {
    /**
     * The [Avro] instance this decoder decodes with: a custom serializer reads its [Avro.configuration] (e.g. the
     * [AvroConfiguration.logicalTypes]) and its [Avro.serializersModule] from it, as with kotlinx-serialization's
     * `JsonDecoder.json`.
     */
    public val avro: Avro

    /**
     * Provides the schema used to encode the current value.
     *
     * Never a union: when the value was written with a union, it is the union's branch holding the value (the branch of
     * the written index, the branch of a default's json, or the branch of a generic value).
     */
    @ExperimentalAvro4kApi
    public val currentWriterSchema: ResolvedSchema

    /**
     * Decode a [AvroSchema.Type.BYTES] value.
     *
     * A bytes value is a sequence of bytes prefixed with an int corresponding to its length.
     */
    @ExperimentalAvro4kApi
    public fun decodeBytes(): ByteArray

    /**
     * Decode a [AvroSchema.Type.FIXED] value, as its bytes.
     *
     * A fixed value is a fixed-size sequence of bytes, where the length is not materialized in the binary output as it is known by the [currentWriterSchema].
     */
    @ExperimentalAvro4kApi
    public fun decodeFixed(): ByteArray
}

internal fun AvroDecoder.unsupportedWriterTypeError(
    mainType: AvroSchema.Type,
    vararg fallbackTypes: AvroSchema.Type,
): Throwable {
    val fallbacksStr = if (fallbackTypes.isNotEmpty()) ", and also not matching to any compatible type (one of ${fallbackTypes.joinToString()})." else ""
    // the binary decoder's own schema: building the message must not resolve a union by reading its index
    val schema = (this as? AbstractAvroDirectDecoder)?.currentAvroSchema ?: currentWriterSchema
    return AvroSchemaMismatchException(
        "Unsupported schema named '${schema.fullNameForMessages}' for decoded type of $mainType$fallbacksStr. Actual schema: ${schema.toSchemaJsonString()}"
    )
}