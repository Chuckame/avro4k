package com.github.avrokotlin.avro4k.internal.codec

import com.github.avrokotlin.avro4k.internal.encodeToUtf8

/**
 * Writes Avro binary into an array it owns, byte for byte as Apache Avro's `BinaryEncoder` does, and hands the bytes
 * written with [toByteArray]. The mirror of [ByteArrayDecoder]: an array and a position.
 *
 * The array starts small, enough for a typical record, and doubles when full; [toByteArray] copies the bytes written
 * into an array of their exact size.
 */
internal class ByteArrayEncoder : AvroBinaryEncoder() {
    private var bytes = ByteArray(INITIAL_CAPACITY)
    private var position = 0

    /** A new array holding every byte written so far. */
    fun toByteArray(): ByteArray = bytes.copyOf(position)

    /** Writes [value] as is, outside of any Avro type: the single-object header. */
    fun writeByte(value: Byte) {
        if (position == bytes.size) {
            grow(1)
        }
        bytes[position++] = value
    }

    override fun writeNull() {
    }

    override fun writeBoolean(value: Boolean) {
        writeByte(if (value) 1 else 0)
    }

    /**
     * Most ints, lengths and indexes take a single byte, and that case is all this function writes itself: it stays
     * small enough to be inlined into every caller, while the longer encodings and the growth are written out of line.
     */
    override fun writeInt(value: Int) {
        val zigZag = (value shl 1) xor (value shr 31)
        val p = position
        if (zigZag and 0x7F.inv() == 0 && p < bytes.size) {
            bytes[p] = zigZag.toByte()
            position = p + 1
        } else {
            writeVarInt(value)
        }
    }

    /** Kept small as [writeInt]. */
    override fun writeLong(value: Long) {
        val zigZag = (value shl 1) xor (value shr 63)
        val p = position
        if (zigZag and 0x7FL.inv() == 0L && p < bytes.size) {
            bytes[p] = zigZag.toByte()
            position = p + 1
        } else {
            writeVarLong(value)
        }
    }

    // `toBits`, not `toRawBits`: every NaN is written as the canonical one, as kotlinx-io's `writeFloatLe` did
    override fun writeFloat(value: Float) {
        val bits = value.toBits()
        val p = reserve(4)
        val bytes = bytes
        bytes[p] = bits.toByte()
        bytes[p + 1] = (bits ushr 8).toByte()
        bytes[p + 2] = (bits ushr 16).toByte()
        bytes[p + 3] = (bits ushr 24).toByte()
    }

    override fun writeDouble(value: Double) {
        val bits = value.toBits()
        val p = reserve(8)
        val bytes = bytes
        bytes[p] = bits.toByte()
        bytes[p + 1] = (bits ushr 8).toByte()
        bytes[p + 2] = (bits ushr 16).toByte()
        bytes[p + 3] = (bits ushr 24).toByte()
        bytes[p + 4] = (bits ushr 32).toByte()
        bytes[p + 5] = (bits ushr 40).toByte()
        bytes[p + 6] = (bits ushr 48).toByte()
        bytes[p + 7] = (bits ushr 56).toByte()
    }

    override fun writeString(value: String) {
        if (value.isEmpty()) {
            writeZero()
            return
        }
        // TODO: encode straight into the array instead of allocating the intermediate one
        writeBytes(value.encodeToUtf8())
    }

    override fun writeString(utf8: ByteArray) {
        writeBytes(utf8)
    }

    override fun writeBytes(value: ByteArray) {
        if (value.isEmpty()) {
            writeZero()
            return
        }
        writeInt(value.size)
        writeFixed(value)
    }

    override fun writeFixed(value: ByteArray) {
        // reserved first: it may replace the array
        val p = reserve(value.size)
        value.copyInto(bytes, p)
    }

    override fun writeEnum(index: Int) {
        writeInt(index)
    }

    override fun writeIndex(index: Int) {
        writeInt(index)
    }

    override fun writeArrayStart(count: Long) {
        writeItemCount(count)
    }

    override fun writeMapStart(count: Long) {
        writeItemCount(count)
    }

    override fun startItem() {
    }

    override fun writeArrayEnd() {
        writeZero()
    }

    override fun writeMapEnd() {
        writeZero()
    }

    /**
     * A collection is written as a single block: its count, its items, then the terminating empty block written by
     * [writeArrayEnd] / [writeMapEnd]. An empty collection is only that terminating block.
     */
    private fun writeItemCount(count: Long) {
        if (count > 0L) {
            writeLong(count)
        }
    }

    private fun writeZero() {
        writeByte(0)
    }

    private fun writeVarInt(value: Int) {
        val p = reserveUpTo(MAX_VARINT_INT_BYTES)
        position = p + encodeZigZagInt(value, bytes, p)
    }

    private fun writeVarLong(value: Long) {
        val p = reserveUpTo(MAX_VARINT_LONG_BYTES)
        position = p + encodeZigZagLong(value, bytes, p)
    }

    /** Makes room for [byteCount] bytes and returns where they go, the position already moved past them. */
    private fun reserve(byteCount: Int): Int {
        val p = reserveUpTo(byteCount)
        position = p + byteCount
        return p
    }

    /** Makes room for at most [byteCount] bytes and returns where they go, leaving the position for the caller to move. */
    private fun reserveUpTo(byteCount: Int): Int {
        if (bytes.size - position < byteCount) {
            grow(byteCount)
        }
        return position
    }

    private fun grow(byteCount: Int) {
        val required = position + byteCount
        if (required < 0 || required > MAX_BINARY_LENGTH) {
            throw IllegalStateException("The encoded value does not fit in an array: more than $MAX_BINARY_LENGTH bytes")
        }
        val doubled = (bytes.size.toLong() shl 1).coerceAtMost(MAX_BINARY_LENGTH.toLong()).toInt()
        bytes = bytes.copyOf(maxOf(doubled, required))
    }
}

/** Enough for a typical record, small enough not to matter when a value is a single field. */
private const val INITIAL_CAPACITY = 256