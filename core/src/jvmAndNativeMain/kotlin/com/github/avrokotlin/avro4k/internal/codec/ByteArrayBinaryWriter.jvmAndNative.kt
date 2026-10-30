package com.github.avrokotlin.avro4k.internal.codec

// The JVM and native have a 64-bit integer: the zig-zag of a long is a few `Long` operations, kept as they were.
internal actual class ByteArrayBinaryWriter actual constructor(
    writeFullChunk: ((chunk: ByteArray, length: Int) -> Unit)?,
) : ChunkedByteArrayBinaryWriter(writeFullChunk) {
    actual override fun writeLong(value: Long) {
        val zigZag = (value shl 1) xor (value shr 63)
        val p = position
        if (zigZag and 0x7FL.inv() == 0L && p < bytes.size) {
            bytes[p] = zigZag.toByte()
            position = p + 1
        } else {
            writeVarLong(value)
        }
    }
}