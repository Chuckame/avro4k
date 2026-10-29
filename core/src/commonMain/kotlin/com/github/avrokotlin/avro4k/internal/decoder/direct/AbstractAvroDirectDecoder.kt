package com.github.avrokotlin.avro4k.internal.decoder.direct

import com.github.avrokotlin.avro4k.Avro
import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchemaMismatchException
import com.github.avrokotlin.avro4k.AvroSerializationException
import com.github.avrokotlin.avro4k.ResolvedSchema
import com.github.avrokotlin.avro4k.internal.DecodingStep
import com.github.avrokotlin.avro4k.internal.NullableUnionField
import com.github.avrokotlin.avro4k.internal.SerializerLocatorMiddleware
import com.github.avrokotlin.avro4k.internal.codec.AvroBinaryDecoder
import com.github.avrokotlin.avro4k.internal.codec.MAX_BINARY_LENGTH
import com.github.avrokotlin.avro4k.internal.codec.decodeUtf8
import com.github.avrokotlin.avro4k.internal.collectionCountError
import com.github.avrokotlin.avro4k.internal.convertDecoded
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
import kotlinx.serialization.internal.AbstractCollectionSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlin.jvm.JvmField

@OptIn(ExperimentalSerializationApi::class)
internal abstract class AbstractAvroDirectDecoder(
    final override val avro: Avro,
    protected val binaryDecoder: AvroBinaryDecoder,
) : AbstractInterceptingDecoder(), AvroDecoder {
    /**
     * The writer schema of the value being decoded; a union until [decodeAndResolveUnion] selects its branch.
     *
     * A plain field that each decoder assigns when it begins a field or an item, rather than a property each decoder
     * overrides: every value decoded reads it, often several times, and a final property compiles to a field read where
     * an overridden one is a virtual call, made through a dispatch stub once the decoder kinds mix in one decode call.
     * A JVM field without accessors, so that assigning it does not check its argument for null either.
     *
     * [NO_ELEMENT_SCHEMA] while no field or item is begun, on a record or block decoder just begun: that marker is a
     * union, so the reads that resolve a union first ([currentWriterSchema], [decodeNotNullMark],
     * [decodeSerializableValue]) meet it on their union path and fail there with [noWriterSchemaError], and the other
     * reads match none of the types they decode and fail on their error path, which checks for it too. The check for a
     * field not begun thus costs nothing to a value of a field that is.
     */
    @JvmField
    var currentAvroSchema: AvroSchema = NO_ELEMENT_SCHEMA

    /**
     * Never a union: one still unresolved (e.g. on the decoder [decodeInlineElement] hands out, before anything is
     * decoded) is resolved here by reading its index, as decoding the value would.
     */
    final override val currentWriterSchema: ResolvedSchema
        get() {
            decodeAndResolveUnion()
            return currentAvroSchema as ResolvedSchema
        }

    /**
     * -1 until the first block of the collection being decoded is read, then the item count of its last block read.
     *
     * Below -1, the count of the next block, already read and checked by [decodeCollectionBlocks] but not yet begun by
     * [beginStructure], stored as its bitwise complement ([Int.inv]: 1 is stored as -2). Packing this into the existing
     * field rather than adding one keeps every decoder at its current size: a record decoder is allocated per record.
     */
    private var decodedCollectionSize = -1

    /** The items of the collection being decoded, summed over its blocks read so far. */
    private var decodedCollectionTotal = 0

    /**
     * The decoders of the records, array blocks, map blocks and polymorphic values this decoder begins, each created on
     * first use and then handed every next structure of its kind, by [beginStructure]: a list of records, or a record's
     * successive fields of one kind, reuse one decoder instead of allocating one per value. Kotlinx decodes depth-first,
     * so a decoder only ever has one structure begun at a time; one it begins inside another is begun by that other's
     * own decoder. They belong to the decode call that created the root decoder, as this one does: nothing is shared
     * between calls.
     *
     * A record matching its writer schema exactly is decoded by [exactRecordDecoder], any other by [recordDecoder].
     *
     * Each of them is in use from its reset to its `endStructure`, a record decoder from its creation, with its first
     * record begun. Its parent then begins the next structure of that kind on a new decoder, kept out of the slot: so a
     * serializer that begins a second structure before ending the first, never ends one, or catches an error inside one
     * and goes on, still decodes correctly, only allocating as it did before decoders were reused. A record or block
     * decoder tells it by a position index of its own, [NOT_IN_USE] once ended (and, for a block decoder, as when
     * created), rather than by a flag in this class, which would make every decoder 8 bytes larger on the JVM. The
     * polymorphic decoder has no such index and a flag of its own, which costs it nothing.
     */
    private var exactRecordDecoder: ExactRecordDirectDecoder? = null
    private var recordDecoder: RecordDirectDecoder? = null
    private var arrayBlockDecoder: ArrayBlockDirectDecoder? = null
    private var mapBlockDecoder: MapBlockDirectDecoder? = null
    private var polymorphicDecoder: PolymorphicDecoder? = null

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
     * collection deserializer reads a single one. So the blocks are merged into one value here, one [AbstractCollectionSerializer.merge] per
     * non-empty block.
     *
     * The count of each block after the first is read here, between two merges, and handed to the next [beginStructure]
     * through [decodedCollectionSize]: the terminating empty block then ends the loop without a merge of its own. avro4k
     * and Apache write a collection as one block followed by that empty one, so most collections take a single merge.
     * A merge of an empty block would cost a block decoder, and kotlinx copying the collection built so far into a new
     * builder and back (an array or a primitive array into a list or a buffer, then into a new array), for no item. The
     * codec still sees the same calls in the same order: a collection start, then one count per next block, the empty
     * one included.
     *
     * This lives on the decoder rather than in a wrapping serializer on purpose: a wrapper would have to be allocated
     * per decoded collection, or cached in shared state that threads and differently-typed collections keep evicting
     * from each other. Here the only state is a local and this decoder's [decodedCollectionSize], and a decoder is only
     * ever used by the one decode call, and so the one thread, that created it. The items are decoded by the block
     * decoder [beginStructure] returns, so a collection nested in an item keeps its state there, not here.
     */
    @OptIn(InternalSerializationApi::class)
    private fun <T> decodeCollectionBlocks(deserializer: AbstractCollectionSerializer<*, T, *>): T {
        decodedCollectionSize = -1
        var result = deserializer.merge(this, null)
        // 0 after an empty block, -1 still when the value was not read in blocks (a record read as a map)
        while (decodedCollectionSize > 0) {
            val nextBlockSize = readNextBlockSize(currentAvroSchema.type == AvroSchema.Type.MAP)
            if (nextBlockSize == 0) break
            decodedCollectionSize = nextBlockSize.inv()
            result = deserializer.merge(this, result)
        }
        return result
    }

    /** The checked item count of the next block of the array or map being decoded, once its first block is read. */
    private fun readNextBlockSize(isMap: Boolean): Int = checkBlockSize(if (isMap) binaryDecoder.mapNext() else binaryDecoder.arrayNext(), firstBlock = false)

    /**
     * The item count of the array or map block that [beginStructure] begins: the one [decodeCollectionBlocks] already
     * read, or else read here, as the collection's first block, or as its next one when this decoder already read one.
     */
    private fun beginBlock(isMap: Boolean): Int {
        val state = decodedCollectionSize
        if (state < -1) {
            val blockSize = state.inv()
            decodedCollectionSize = blockSize
            return blockSize
        }
        if (state != -1) return readNextBlockSize(isMap)
        return checkBlockSize(if (isMap) binaryDecoder.readMapStart() else binaryDecoder.readArrayStart(), firstBlock = true)
    }

    override fun beginStructure(descriptor: SerialDescriptor): CompositeDecoder {
        return when (descriptor.kind) {
            StructureKind.LIST ->
                when (currentAvroSchema.type) {
                    AvroSchema.Type.ARRAY -> arrayBlockDecoder().reset(currentAvroSchema, beginBlock(isMap = false))
                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.ARRAY)
                }

            StructureKind.MAP ->
                when (currentAvroSchema.type) {
                    AvroSchema.Type.MAP -> mapBlockDecoder().reset(currentAvroSchema, beginBlock(isMap = true))

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
                    AvroSchema.Type.RECORD -> beginRecord(currentAvroSchema, descriptor)
                    else -> throw unsupportedWriterTypeError(AvroSchema.Type.RECORD)
                }

            is PolymorphicKind -> {
                // a union is read by the polymorphic decoder: the marker of no field begun must not reach it
                val schema = currentAvroSchema
                if (schema === NO_ELEMENT_SCHEMA) throw noWriterSchemaError()
                polymorphicDecoder(descriptor, schema)
            }

            else -> throw AvroSerializationException("Unsupported descriptor for structure decoding: $descriptor")
        }
    }

    /**
     * Begins a record written with [writerRecordSchema] into [descriptor], on the exact record decoder when its fields
     * match exactly, or else on the general one.
     *
     * A kept decoder that is free and was last resolved for this very schema and descriptor begins it at once: the records
     * of a list, or a field's successive values, are most often of one type, and so never reach the resolver. Compared by
     * identity, as resolving is: a schema or descriptor equal to the previous one but another instance is resolved. The
     * memo of each kind is checked, so two record types alternating, one exact and one not, both stay remembered.
     *
     * Resolved before any decoder is touched: a resolution that fails (two elements matching one writer field) leaves
     * each kept decoder with the steps it had, and the same record begun again fails again.
     */
    private fun beginRecord(
        writerRecordSchema: AvroSchema,
        descriptor: SerialDescriptor,
    ): CompositeDecoder {
        val exact = exactRecordDecoder
        if (exact != null && !exact.inUse && exact.isResolvedFor(writerRecordSchema, descriptor)) return exact.reset()
        val general = recordDecoder
        if (general != null && !general.inUse && general.isResolvedFor(writerRecordSchema, descriptor)) return general.reset()
        val workflow = avro.recordResolver.resolveFields(writerRecordSchema, descriptor)
        val fieldSchemas = workflow.exactFieldSchemas
        return if (fieldSchemas != null) {
            beginExactRecord(writerRecordSchema, descriptor, fieldSchemas, workflow.exactNullableFields)
        } else {
            beginGeneralRecord(writerRecordSchema, descriptor, workflow.decoding)
        }
    }

    /**
     * Begins a record of a type just resolved, decoded exactly as written, on the exact record decoder this decoder keeps,
     * unless it is still in use; or else on a new one, created with the record begun.
     */
    private fun beginExactRecord(
        writerRecordSchema: AvroSchema,
        descriptor: SerialDescriptor,
        fieldSchemas: Array<AvroSchema>,
        nullableFields: Array<NullableUnionField?>,
    ): ExactRecordDirectDecoder {
        val kept = exactRecordDecoder
        if (kept != null && !kept.inUse) return kept.reset(writerRecordSchema, descriptor, fieldSchemas, nullableFields)
        val decoder = ExactRecordDirectDecoder(avro, binaryDecoder, writerRecordSchema, descriptor, fieldSchemas, nullableFields)
        if (kept == null) exactRecordDecoder = decoder
        return decoder
    }

    /**
     * Begins a record of a type just resolved, decoded by its [decodingSteps], on the record decoder this decoder keeps,
     * unless it is still in use; or else on a new one, created with the record begun.
     */
    private fun beginGeneralRecord(
        writerRecordSchema: AvroSchema,
        descriptor: SerialDescriptor,
        decodingSteps: Array<DecodingStep>,
    ): RecordDirectDecoder {
        val kept = recordDecoder
        if (kept != null && !kept.inUse) return kept.reset(writerRecordSchema, descriptor, decodingSteps)
        val decoder = RecordDirectDecoder(avro, binaryDecoder, writerRecordSchema, descriptor, decodingSteps)
        if (kept == null) recordDecoder = decoder
        return decoder
    }

    /** The array block decoder to begin a block on: the one this decoder keeps, unless it is still in use. */
    private fun arrayBlockDecoder(): ArrayBlockDirectDecoder {
        val kept = arrayBlockDecoder
        if (kept != null && !kept.inUse) return kept
        val decoder = ArrayBlockDirectDecoder(avro, binaryDecoder)
        if (kept == null) arrayBlockDecoder = decoder
        return decoder
    }

    /** The map block decoder to begin a block on: the one this decoder keeps, unless it is still in use. */
    private fun mapBlockDecoder(): MapBlockDirectDecoder {
        val kept = mapBlockDecoder
        if (kept != null && !kept.inUse) return kept
        val decoder = MapBlockDirectDecoder(avro, binaryDecoder)
        if (kept == null) mapBlockDecoder = decoder
        return decoder
    }

    /**
     * The polymorphic decoder to begin a value of [descriptor] written with [schema] on: the one this decoder keeps,
     * unless it is still in use.
     */
    private fun polymorphicDecoder(
        descriptor: SerialDescriptor,
        schema: AvroSchema,
    ): PolymorphicDecoder {
        val kept = polymorphicDecoder
        if (kept != null && !kept.inUse) {
            kept.reset(descriptor, schema)
            return kept
        }
        val decoder = PolymorphicDecoder(avro, descriptor, schema, binaryDecoder)
        if (kept == null) polymorphicDecoder = decoder
        return decoder
    }

    /**
     * The part of a reused decoder's reset that this class owns: forgets the collection its previous structure decoded,
     * as a new decoder would not know of one. Called by each reset, which then marks its decoder in use.
     */
    protected fun beginReuse() {
        decodedCollectionSize = -1
        decodedCollectionTotal = 0
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
        val schema = currentAvroSchema
        if (schema.type === AvroSchema.Type.UNION) resolveUnion(schema)
    }

    /** Reads the branch of [union] the value is written with, and makes it the [currentAvroSchema]. */
    private fun resolveUnion(union: AvroSchema) {
        if (union === NO_ELEMENT_SCHEMA) throw noWriterSchemaError()
        val branches = union.types
        val index = binaryDecoder.readIndex()
        if (index < 0 || index >= branches.size) throw unionIndexOutOfRangeError(index, union)
        currentAvroSchema = branches[index]
    }

    /**
     * What this decoder throws when its writer schema is asked for while no field or item is begun
     * ([NO_ELEMENT_SCHEMA]): a reused decoder fails there as a new one does, instead of answering with what its previous
     * structure left behind.
     */
    internal open fun noWriterSchemaError(): Throwable = noElementBegunError()

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
            AvroSchema.Type.STRING -> binaryDecoder.readStringBytes().decodeUtf8().convertDecoded(AvroSchema.Type.STRING, "Char") { it.single() }
            else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.STRING)
        }
    }

    override fun decodeString(): String {
        return when (currentAvroSchema.type) {
            AvroSchema.Type.STRING -> binaryDecoder.readString()

            AvroSchema.Type.BYTES -> binaryDecoder.readBytes().decodeUtf8()

            AvroSchema.Type.FIXED -> binaryDecoder.readFixed(currentAvroSchema.fixedSize).decodeUtf8()

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

/**
 * The [AbstractAvroDirectDecoder.currentAvroSchema] of a record or block decoder while no field or item is begun. Only
 * ever compared by identity; never a writer schema, as it is no schema the decoders build. A union, so that the reads
 * that resolve a union first meet it on their union path.
 */
internal val NO_ELEMENT_SCHEMA: AvroSchema = AvroSchema.UnionSchema(AvroSchema.NullSchema())

/**
 * What a decoder throws when asked for the writer schema of the field or item it decodes, or for the field's decoding
 * step, before one is begun: a reused decoder fails there as a new one does, instead of answering with what its previous
 * structure left behind.
 */
internal fun noElementBegunError(): IllegalStateException =
    IllegalStateException("No field or item is being decoded: decodeElementIndex or a decode...Element call begins one")

/**
 * The position index of a reusable decoder that decodes no structure: created and not reset yet, or ended. Any index of
 * a structure being decoded is 0 or more, so the index also tells whether the decoder is in use.
 */
internal const val NOT_IN_USE = -1