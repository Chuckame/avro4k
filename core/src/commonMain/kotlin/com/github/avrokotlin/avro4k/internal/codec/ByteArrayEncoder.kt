package com.github.avrokotlin.avro4k.internal.codec

/**
 * Writes Avro binary into an array it owns, byte for byte as Apache Avro's `BinaryEncoder` does, and hands the bytes
 * written with [toByteArray]. The mirror of [ByteArrayDecoder]: an array and a position.
 *
 * The bytes go into chunks: the first one small, enough for a typical record, each next one twice the previous up to
 * [MAX_CHUNK_SIZE], so that growing never copies what is already written. [toByteArray] copies the chunks into an array
 * of their exact size.
 *
 * With [writeFullChunk], each full chunk is handed to it instead of being kept, and its array reused once the chunks
 * have reached [MAX_CHUNK_SIZE]: the memory held stays bounded by the chunk size whatever the value's size. Only
 * [writeChunksTo] then hands the rest, [toByteArray] no longer holding every byte.
 */
internal class ByteArrayEncoder(
    private val writeFullChunk: ((chunk: ByteArray, length: Int) -> Unit)? = null,
) : AvroBinaryEncoder() {
    /** The chunk being written. */
    private var bytes = ByteArray(FIRST_CHUNK_SIZE)
    private var position = 0

    /** The chunks before [bytes], none until the first one is full. */
    private var fullChunks: ArrayList<ByteArray>? = null

    /** The number of bytes written in each of [fullChunks]: a chunk can end with a few unused bytes. */
    private var fullChunkLengths = IntArray(0)
    private var fullSize = 0

    /** A new array holding every byte written so far. */
    fun toByteArray(): ByteArray {
        val chunks = fullChunks ?: return bytes.copyOf(position)
        val result = ByteArray(fullSize + position)
        var offset = 0
        for (i in chunks.indices) {
            val length = fullChunkLengths[i]
            chunks[i].copyInto(result, offset, 0, length)
            offset += length
        }
        bytes.copyInto(result, offset, 0, position)
        return result
    }

    /** Hands every byte written so far to [write], without copying them: each chunk in order, with its used length. */
    fun writeChunksTo(write: (chunk: ByteArray, length: Int) -> Unit) {
        fullChunks?.let { chunks ->
            for (i in chunks.indices) {
                write(chunks[i], fullChunkLengths[i])
            }
        }
        write(bytes, position)
    }

    /** Writes [value] as is, outside of any Avro type: the single-object header. */
    fun writeByte(value: Byte) {
        if (position == bytes.size) {
            nextChunk(1)
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

    // `toBits`, not `toRawBits`: every NaN is written as the canonical one, as Apache Avro's `floatToIntBits` does
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

    /**
     * Encodes [value] straight into the chunk, with no array of its UTF-8 bytes in between.
     *
     * A string of at most [MAX_ONE_BYTE_LENGTH_CHARS] chars, most of them, is written in one pass: its length always
     * takes one byte, so that byte is reserved, the chars encoded after it, and the length written back into it. This
     * function writes that case itself and stays small enough to be inlined; a longer string, or one that may not fit
     * in the room left in the chunk, is counted first, out of line by [writeCountedString].
     */
    override fun writeString(value: String) {
        val length = value.length
        val p = position
        val bytes = bytes
        // the one-byte length, then at most 3 bytes per char
        if (length <= MAX_ONE_BYTE_LENGTH_CHARS && bytes.size - p > 3 * length) {
            val end = value.encodeUtf8Into(bytes, p + 1, 0, length)
            // the zig-zag encoding of a length below 64, in a single byte
            bytes[p] = ((end - p - 1) shl 1).toByte()
            position = end
        } else {
            writeCountedString(value)
        }
    }

    /**
     * Writes the length of [value], counted beforehand, then its UTF-8 bytes: into the room left in the current chunk,
     * and the rest at the start of the next one, as [writeAcrossChunks] does.
     *
     * When they do not all fit, the chars are encoded by runs that surely fit in the room left, 3 bytes per char, so
     * that no char is split between two chunks; the last few bytes of the chunk may stay unused, as before a varint.
     */
    private fun writeCountedString(value: String) {
        val length = value.length
        var remaining = value.utf8Length()
        writeInt(remaining)
        var i = 0
        while (true) {
            val p = position
            val room = bytes.size - p
            if (remaining <= room) {
                position = value.encodeUtf8Into(bytes, p, i, length)
                return
            }
            // fewer chars than are left, since the rest takes more than the room and at most 3 bytes per char
            var end = i + room / 3
            // a surrogate pair stays whole in the next run
            if (end > i && value[end - 1].isHighSurrogate()) end--
            if (end == i) {
                nextChunk(remaining)
            } else {
                val q = value.encodeUtf8Into(bytes, p, i, end)
                remaining -= q - p
                position = q
                i = end
            }
        }
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
        val p = position
        if (bytes.size - p >= value.size) {
            value.copyInto(bytes, p)
            position = p + value.size
        } else {
            writeAcrossChunks(value)
        }
    }

    /** Fills the current chunk with the start of [value], and puts the rest at the start of the next one. */
    private fun writeAcrossChunks(value: ByteArray) {
        val head = bytes.size - position
        value.copyInto(bytes, position, 0, head)
        position += head
        nextChunk(value.size - head)
        value.copyInto(bytes, 0, head, value.size)
        position = value.size - head
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

    /**
     * Makes room for [byteCount] contiguous bytes and returns where they go in [bytes], the position already moved past
     * them.
     */
    private fun reserve(byteCount: Int): Int {
        val p = reserveUpTo(byteCount)
        position = p + byteCount
        return p
    }

    /**
     * Makes room for at most [byteCount] contiguous bytes, a varint or a number, and returns where they go in [bytes],
     * leaving the position for the caller to move. The few bytes left at the end of a full chunk stay unused.
     */
    private fun reserveUpTo(byteCount: Int): Int {
        if (bytes.size - position < byteCount) {
            nextChunk(byteCount)
        }
        return position
    }

    /** Keeps the current chunk with the bytes written in it, and starts a new one of at least [byteCount] bytes. */
    private fun nextChunk(byteCount: Int) {
        val writeFullChunk = writeFullChunk
        if (writeFullChunk != null) {
            writeFullChunk(bytes, position)
            if (bytes.size < MAX_CHUNK_SIZE || bytes.size < byteCount) {
                bytes = ByteArray(maxOf((bytes.size shl 1).coerceAtMost(MAX_CHUNK_SIZE), byteCount))
            }
            position = 0
            return
        }
        val size = fullSize.toLong() + position + byteCount
        if (size > MAX_BINARY_LENGTH) {
            throw IllegalStateException("The encoded value does not fit in an array: more than $MAX_BINARY_LENGTH bytes")
        }
        val chunks = fullChunks ?: ArrayList<ByteArray>().also { fullChunks = it }
        if (chunks.size == fullChunkLengths.size) {
            fullChunkLengths = fullChunkLengths.copyOf(maxOf(8, chunks.size * 2))
        }
        fullChunkLengths[chunks.size] = position
        chunks.add(bytes)
        fullSize += position
        bytes = ByteArray(maxOf((bytes.size shl 1).coerceAtMost(MAX_CHUNK_SIZE), byteCount))
        position = 0
    }
}

/**
 * The longest string whose length is surely written in one byte: a UTF-16 char takes at most 3 bytes in UTF-8 (2 below
 * U+0800, 3 for the rest of the basic plane, 4 for a surrogate pair of 2 chars, 1 for an unpaired surrogate written as
 * `?`), so 21 chars take at most 63 bytes, whose zig-zag varint, 126, is below 128. 22 chars can take 66 bytes, 2 for
 * their length.
 */
private const val MAX_ONE_BYTE_LENGTH_CHARS = 21

/** Enough for a typical record, small enough not to matter when a value is a single field. */
private const val FIRST_CHUNK_SIZE = 256

/** Large enough for the copies into the final array to be few, small enough not to waste much of the last chunk. */
private const val MAX_CHUNK_SIZE = 64 * 1024