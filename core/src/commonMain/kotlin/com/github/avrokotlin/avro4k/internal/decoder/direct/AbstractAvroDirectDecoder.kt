package com.github.avrokotlin.avro4k.internal.decoder.direct

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.AvroSerializationException
import com.github.avrokotlin.avro4k.ResolvedSchema
import com.github.avrokotlin.avro4k.internal.SerializerLocatorMiddleware
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryDecoder
import com.github.avrokotlin.avro4k.internal.codec.MAX_BINARY_LENGTH
import com.github.avrokotlin.avro4k.internal.collectionCountError
import com.github.avrokotlin.avro4k.internal.convertDecoded
import com.github.avrokotlin.avro4k.internal.decoder.AbstractPolymorphicDecoder
import com.github.avrokotlin.avro4k.internal.enumIndexOutOfRangeError
import com.github.avrokotlin.avro4k.internal.isFullNameOrAliasMatch
import com.github.avrokotlin.avro4k.internal.schema.enumSymbols
import com.github.avrokotlin.avro4k.internal.schema.fixedSize
import com.github.avrokotlin.avro4k.internal.schema.toSchemaJsonString
import com.github.avrokotlin.avro4k.internal.schema.types
import com.github.avrokotlin.avro4k.internal.toByteExact
import com.github.avrokotlin.avro4k.internal.toFloatExact
import com.github.avrokotlin.avro4k.internal.toIntExact
import com.github.avrokotlin.avro4k.internal.toShortExact
import com.github.avrokotlin.avro4k.internal.unionIndexOutOfRangeError
import com.github.avrokotlin.avro4k.unsupportedWriterTypeError
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.internal.AbstractCollectionSerializer
import kotlinx.serialization.modules.SerializersModule

@OptIn(ExperimentalSerializationApi::class)
internal abstract class AbstractAvroDirectDecoder(
    final override val avro: Avro,
    protected val binaryDecoder: AvroBinaryDecoder,
) : AbstractInterceptingDecoder(), AvroDecoder {
    /** The writer schema of the value being decoded; a union until [decodeAndResolveUnion] selects its branch. */
    abstract var currentAvroSchema: AvroSchema

    /**
     * Never a union: one still unresolved (e.g. on the decoder [decodeInlineElement] hands out, before anything is
     * decoded) is resolved here by reading its index, as decoding the value would.
     */
    final override val currentWriterSchema: ResolvedSchema
        get() {
            decodeAndResolveUnion()
            return currentAvroSchema as ResolvedSchema
        }

    /** -1 until the first block of the collection being decoded is read, then the item count of its last block read. */
    private var decodedCollectionSize = -1

    /** The items of the collection being decoded, summed over its blocks read so far. */
    private var decodedCollectionTotal = 0

    override val serializersModule: SerializersModule
        get() = avro.serializersModule

    @OptIn(InternalSerializationApi::class)
    override fun <T> decodeSerializableValue(deserializer: DeserializationStrategy<T>): T {
        decodeAndResolveUnion()
        val actualDeserializer = SerializerLocatorMiddleware.apply(deserializer)
        if (actualDeserializer is AbstractCollectionSerializer<*, T, *>) {
            return decodeCollectionBlocks(actualDeserializer)
        }
        return actualDeserializer.deserialize(this)
    }

    /**
     * Avro writes an array or a map as a sequence of blocks (`count, items..., count, items..., 0`), while a kotlinx
     * collection deserializer reads a single one. So the blocks are merged into one value here, one [AbstractCollectionSerializer.merge] per block,
     * until the decoder reads the terminating empty block.
     *
     * This lives on the decoder rather than in a wrapping serializer on purpose: a wrapper would have to be allocated
     * per decoded collection, or cached in shared state that threads and differently-typed collections keep evicting
     * from each other. Here the only state is a local and this decoder's [decodedCollectionSize], and a decoder is only
     * ever used by the one decode call, and so the one thread, that created it.
     */
    @OptIn(InternalSerializationApi::class)
    private fun <T> decodeCollectionBlocks(deserializer: AbstractCollectionSerializer<*, T, *>): T {
        var result: T? = null
        decodedCollectionSize = -1
        do {
            result = deserializer.merge(this, result)
        } while (decodedCollectionSize > 0)
        return result
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        return when (descriptor.kind) {
            StructureKind.LIST ->
                when (currentAvroSchema.type) {
                    AvroSchema.Type.ARRAY -> {
                        val firstBlock = decodedCollectionSize == -1
                        val blockSize = checkBlockSize(if (firstBlock) binaryDecoder.readArrayStart() else binaryDecoder.arrayNext(), firstBlock)
                        ArrayBlockDirectDecoder(currentAvroSchema, blockSize, avro, binaryDecoder)
                    }

                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.ARRAY)
                }

            StructureKind.MAP ->
                when (currentAvroSchema.type) {
                    AvroSchema.Type.MAP -> {
                        val firstBlock = decodedCollectionSize == -1
                        val blockSize = checkBlockSize(if (firstBlock) binaryDecoder.readMapStart() else binaryDecoder.mapNext(), firstBlock)
                        MapBlockDirectDecoder(currentAvroSchema, blockSize, avro, binaryDecoder)
                    }

                    AvroSchema.Type.RECORD ->
                        RecordAsMapDirectDecoder(
                            currentAvroSchema,
                            avro,
                            binaryDecoder
                        )

                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.MAP)
                }

            StructureKind.CLASS, StructureKind.OBJECT ->
                when (currentAvroSchema.type) {
                    AvroSchema.Type.RECORD -> RecordDirectDecoder(currentAvroSchema, descriptor, avro, binaryDecoder)
                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.RECORD)
                }

            is PolymorphicKind -> PolymorphicDecoder(avro, descriptor, currentAvroSchema, binaryDecoder)

            else -> throw AvroSerializationException("Unsupported descriptor for structure decoding: $descriptor")
        }
    }

    /**
     * The item count of a collection block read from the input, checked before anything is allocated for it: the
     * collection's total count, summed over its blocks, must stay within [MAX_BINARY_LENGTH] (the bound of strings and
     * bytes), so that a negative, overflowing or larger-than-[Int] count fails as malformed data instead of being
     * truncated to an [Int]. How much is allocated for a large block is bounded by [MAX_PRESIZED_BLOCK_ITEMS].
     */
    private fun checkBlockSize(
        count: Long,
        firstBlock: Boolean,
    ): Int {
        val previousTotal = if (firstBlock) 0 else decodedCollectionTotal
        if (count < 0L || count > MAX_BINARY_LENGTH - previousTotal) {
            throw collectionCountError(count, previousTotal)
        }
        val blockSize = count.toInt()
        decodedCollectionTotal = previousTotal + blockSize
        decodedCollectionSize = blockSize
        return blockSize
    }

    private fun decodeAndResolveUnion() {
        if (currentAvroSchema.type == AvroSchema.Type.UNION) {
            val branches = currentAvroSchema.types
            val index = binaryDecoder.readIndex()
            if (index < 0 || index >= branches.size) throw unionIndexOutOfRangeError(index, currentAvroSchema)
            currentAvroSchema = branches[index]
        }
    }

    /** The symbol of the enum index read from the input, checked against the writer schema's symbols. */
    private fun readEnumSymbol(): String {
        val symbols = currentAvroSchema.enumSymbols
        val index = binaryDecoder.readEnum()
        if (index < 0 || index >= symbols.size) throw enumIndexOutOfRangeError(index, currentAvroSchema)
        return symbols[index]
    }

    override fun decodeNotNullMark(): Boolean {
        decodeAndResolveUnion()

        return currentAvroSchema.type != AvroSchema.Type.NULL
    }

    override fun decodeNull(): Nothing? {
        if (currentAvroSchema.type != AvroSchema.Type.NULL) {
            throw unsupportedWriterTypeError(AvroSchema.Type.NULL)
        }
        binaryDecoder.readNull()
        return null
    }

    override fun decodeBoolean(): Boolean {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.BOOLEAN -> binaryDecoder.readBoolean()
            AvroSchema.Type.STRING -> binaryDecoder.readString().convertDecoded(AvroSchema.Type.STRING, "Boolean") { it.toBooleanStrict() }
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.BOOLEAN, AvroSchema.Type.STRING)
        }
    }

    override fun decodeByte(): Byte {
        return decodeInt().toByteExact()
    }

    override fun decodeShort(): Short {
        return decodeInt().toShortExact()
    }

    override fun decodeInt(): Int {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.INT -> binaryDecoder.readInt()
            AvroSchema.Type.LONG -> binaryDecoder.readLong().toIntExact()
            AvroSchema.Type.STRING -> binaryDecoder.readString().convertDecoded(AvroSchema.Type.STRING, "Int") { it.toInt() }
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.STRING)
        }
    }

    override fun decodeLong(): Long {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.LONG -> binaryDecoder.readLong()
            AvroSchema.Type.INT -> binaryDecoder.readInt().toLong()
            AvroSchema.Type.STRING -> binaryDecoder.readString().convertDecoded(AvroSchema.Type.STRING, "Long") { it.toLong() }
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.LONG, AvroSchema.Type.INT, AvroSchema.Type.STRING)
        }
    }

    override fun decodeFloat(): Float {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.FLOAT -> binaryDecoder.readFloat()
            AvroSchema.Type.DOUBLE -> binaryDecoder.readDouble().toFloatExact()
            AvroSchema.Type.STRING -> binaryDecoder.readString().convertDecoded(AvroSchema.Type.STRING, "Float") { it.toFloat() }
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.FLOAT, AvroSchema.Type.DOUBLE, AvroSchema.Type.STRING)
        }
    }

    override fun decodeDouble(): Double {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.FLOAT -> binaryDecoder.readFloat().toDouble()
            AvroSchema.Type.DOUBLE -> binaryDecoder.readDouble()
            AvroSchema.Type.STRING -> binaryDecoder.readString().convertDecoded(AvroSchema.Type.STRING, "Double") { it.toDouble() }
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.DOUBLE, AvroSchema.Type.FLOAT, AvroSchema.Type.STRING)
        }
    }

    override fun decodeChar(): Char {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.INT -> binaryDecoder.readInt().toChar()
            AvroSchema.Type.STRING -> binaryDecoder.readStringBytes().decodeToString().convertDecoded(AvroSchema.Type.STRING, "Char") { it.single() }
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.STRING)
        }
    }

    override fun decodeString(): String {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.STRING -> binaryDecoder.readString()

            AvroSchema.Type.BYTES -> binaryDecoder.readBytes().decodeToString()

            AvroSchema.Type.FIXED -> binaryDecoder.readFixed(currentAvroSchema.fixedSize).decodeToString()

            AvroSchema.Type.BOOLEAN -> binaryDecoder.readBoolean().toString()

            AvroSchema.Type.INT -> binaryDecoder.readInt().toString()

            AvroSchema.Type.LONG -> binaryDecoder.readLong().toString()

            AvroSchema.Type.FLOAT -> binaryDecoder.readFloat().toString()

            AvroSchema.Type.DOUBLE -> binaryDecoder.readDouble().toString()

            AvroSchema.Type.ENUM -> readEnumSymbol()

            else -> throw unsupportedWriterTypeError(
                AvroSchema.Type.STRING,
                AvroSchema.Type.BYTES,
                AvroSchema.Type.FIXED,
                AvroSchema.Type.BOOLEAN,
                AvroSchema.Type.INT,
                AvroSchema.Type.LONG,
                AvroSchema.Type.FLOAT,
                AvroSchema.Type.DOUBLE,
                AvroSchema.Type.ENUM
            )
        }
    }

    override fun decodeEnum(enumDescriptor: SerialDescriptor): Int {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.ENUM -> {
                if (currentAvroSchema.isFullNameOrAliasMatch(enumDescriptor)) {
                    enumDescriptor.getEnumIndex(readEnumSymbol())
                } else {
                    throw unsupportedWriterTypeError(AvroSchema.Type.ENUM, AvroSchema.Type.STRING)
                }
            }

            AvroSchema.Type.STRING -> {
                val enumSymbol = binaryDecoder.readString()
                enumDescriptor.getEnumIndex(enumSymbol)
            }

            else -> throw unsupportedWriterTypeError(AvroSchema.Type.ENUM, AvroSchema.Type.STRING)
        }
    }

    private fun SerialDescriptor.getEnumIndex(enumName: String): Int {
        val idx = getElementIndex(enumName)
        return if (idx >= 0) {
            idx
        } else {
            avro.enumResolver.getDefaultValueIndex(this)
                ?: throw AvroSchemaMismatchException(
                    "Unknown enum symbol name '$enumName' for Enum '${this.serialName}' for writer schema ${currentAvroSchema.toSchemaJsonString()}"
                )
        }
    }

    override fun decodeBytes(): ByteArray {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.BYTES -> binaryDecoder.readBytes()
            AvroSchema.Type.FIXED -> binaryDecoder.readFixed(currentAvroSchema.fixedSize)
            AvroSchema.Type.STRING -> binaryDecoder.readStringBytes()
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.BYTES, AvroSchema.Type.FIXED, AvroSchema.Type.STRING)
        }
    }

    override fun decodeFixed(): ByteArray {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.BYTES -> binaryDecoder.readBytes()
            AvroSchema.Type.FIXED -> binaryDecoder.readFixed(currentAvroSchema.fixedSize)
            AvroSchema.Type.STRING -> binaryDecoder.readStringBytes()
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.FIXED, AvroSchema.Type.BYTES, AvroSchema.Type.STRING)
        }
    }
}

private class PolymorphicDecoder(
    avro: Avro,
    descriptor: SerialDescriptor,
    schema: AvroSchema,
    private val binaryDecoder: AvroBinaryDecoder,
) : AbstractPolymorphicDecoder(avro, descriptor, schema) {
    override fun tryFindSerialNameForUnion(
        namesAndAliasesToSerialName: Map<String, String>,
        schema: AvroSchema,
    ): Pair<String, AvroSchema>? {
        val branches = schema.types
        val index = binaryDecoder.readIndex()
        if (index < 0 || index >= branches.size) throw unionIndexOutOfRangeError(index, schema)
        return tryFindSerialName(namesAndAliasesToSerialName, branches[index])
    }

    override fun newDecoder(chosenSchema: AvroSchema): Decoder {
        return AvroValueDirectDecoder(chosenSchema, avro, binaryDecoder)
    }
}