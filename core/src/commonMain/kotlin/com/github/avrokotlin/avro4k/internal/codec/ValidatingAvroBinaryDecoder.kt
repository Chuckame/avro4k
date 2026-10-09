package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.AvroSchema.Type

/**
 * A decoder that checks every call against the writer schema of one datum before forwarding it to [decoder], for
 * [com.github.avrokotlin.avro4k.AvroConfiguration.validateSerialization]: see [WriterSchemaCallValidator] for what is
 * checked. A call that does not match throws a [kotlinx.serialization.SerializationException] and is not forwarded;
 * a read enum or union index, or a block's item count, is checked once read.
 *
 * Collections follow the block protocol of [AvroBinaryDecoder]: `readArrayStart()` then `arrayNext()` once each
 * block's items are read, until a block of 0 items; or, to skip one, `skipArray()` until it returns 0, the items of each
 * non-empty block being skipped one by one in between (the same for maps, each entry being its string key then its
 * value). Every value call is forwarded unchanged, so the wrapped decoder sees exactly the calls it would see without
 * validation.
 *
 * Created for each decoded value; call [checkDatumComplete] once the value is decoded.
 */
internal class ValidatingAvroBinaryDecoder(
    private val decoder: AvroBinaryDecoder,
    writerSchema: AvroSchema,
) : AvroBinaryDecoder() {
    private val validator = WriterSchemaCallValidator(writerSchema, decoding = true)

    /** Checks that the whole datum was read. */
    fun checkDatumComplete() {
        validator.checkDatumComplete()
    }

    override fun readNull() {
        validator.primitive(Type.NULL, "readNull()")
        decoder.readNull()
    }

    override fun readBoolean(): Boolean {
        validator.primitive(Type.BOOLEAN, "readBoolean()")
        return decoder.readBoolean()
    }

    override fun readInt(): Int {
        validator.primitive(Type.INT, "readInt()")
        return decoder.readInt()
    }

    override fun readLong(): Long {
        validator.primitive(Type.LONG, "readLong()")
        return decoder.readLong()
    }

    override fun readFloat(): Float {
        validator.primitive(Type.FLOAT, "readFloat()")
        return decoder.readFloat()
    }

    override fun readDouble(): Double {
        validator.primitive(Type.DOUBLE, "readDouble()")
        return decoder.readDouble()
    }

    override fun readString(): String {
        validator.primitive(Type.STRING, "readString()")
        return decoder.readString()
    }

    override fun readStringBytes(): ByteArray {
        validator.primitive(Type.STRING, "readStringBytes()")
        return decoder.readStringBytes()
    }

    override fun skipString() {
        validator.primitive(Type.STRING, "skipString()")
        decoder.skipString()
    }

    override fun readBytes(): ByteArray {
        validator.primitive(Type.BYTES, "readBytes()")
        return decoder.readBytes()
    }

    override fun skipBytes() {
        validator.primitive(Type.BYTES, "skipBytes()")
        decoder.skipBytes()
    }

    override fun readFixed(size: Int): ByteArray {
        validator.fixed(size, "readFixed()")
        return decoder.readFixed(size)
    }

    override fun skipFixed(size: Int) {
        validator.fixed(size, "skipFixed()")
        decoder.skipFixed(size)
    }

    override fun readEnum(): Int {
        validator.beginEnum("readEnum()")
        val index = decoder.readEnum()
        validator.enumIndex(index, "readEnum()")
        return index
    }

    override fun readIndex(): Int {
        validator.beginUnion("readIndex()")
        val index = decoder.readIndex()
        validator.unionIndex(index, "readIndex()")
        return index
    }

    override fun readArrayStart(): Long {
        validator.beginCollection(Type.ARRAY, "readArrayStart()")
        val count = decoder.readArrayStart()
        validator.firstBlock(count, skipped = false, "readArrayStart()")
        return count
    }

    override fun arrayNext(): Long {
        validator.beginNextBlock(Type.ARRAY, skipped = false, "arrayNext()")
        val count = decoder.arrayNext()
        validator.nextBlock(count, "arrayNext()")
        return count
    }

    override fun skipArray(): Long {
        val isNextBlock = validator.awaitsNextBlock(Type.ARRAY, skipped = true)
        if (!isNextBlock) validator.beginCollection(Type.ARRAY, "skipArray()")
        val count = decoder.skipArray()
        if (isNextBlock) {
            validator.nextBlock(count, "skipArray()")
        } else {
            validator.firstBlock(count, skipped = true, "skipArray()")
        }
        return count
    }

    override fun readMapStart(): Long {
        validator.beginCollection(Type.MAP, "readMapStart()")
        val count = decoder.readMapStart()
        validator.firstBlock(count, skipped = false, "readMapStart()")
        return count
    }

    override fun mapNext(): Long {
        validator.beginNextBlock(Type.MAP, skipped = false, "mapNext()")
        val count = decoder.mapNext()
        validator.nextBlock(count, "mapNext()")
        return count
    }

    override fun skipMap(): Long {
        val isNextBlock = validator.awaitsNextBlock(Type.MAP, skipped = true)
        if (!isNextBlock) validator.beginCollection(Type.MAP, "skipMap()")
        val count = decoder.skipMap()
        if (isNextBlock) {
            validator.nextBlock(count, "skipMap()")
        } else {
            validator.firstBlock(count, skipped = true, "skipMap()")
        }
        return count
    }
}