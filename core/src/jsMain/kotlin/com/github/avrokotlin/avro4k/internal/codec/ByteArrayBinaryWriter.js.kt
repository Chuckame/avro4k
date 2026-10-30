package com.github.avrokotlin.avro4k.internal.codec

// Kotlin/JS emulates a `Long` with an object of two 32-bit halves, each shift, mask or comparison on it a call that
// allocates a new one. The halves are taken once, and the zig-zag is done on them as `Int`s.
internal actual class ByteArrayBinaryWriter actual constructor(
    writeFullChunk: ((chunk: ByteArray, length: Int) -> Unit)?,
) : ChunkedByteArrayBinaryWriter(writeFullChunk) {
    actual override fun writeLong(value: Long) {
        val low = value.toInt()
        val high = (value ushr 32).toInt()
        // the long takes one byte when it is an int of one byte: between -64 and 63
        val zigZag = (low shl 1) xor (low shr 31)
        val p = position
        if (high == (low shr 31) && zigZag and 0x7F.inv() == 0 && p < bytes.size) {
            bytes[p] = zigZag.toByte()
            position = p + 1
        } else {
            writeVarLong(low, high)
        }
    }
}