package com.github.avrokotlin.benchmark.internal

/**
 * Byte-for-byte comparison of two Avro encodings of the same logical value.
 *
 * This is the half of [EquivalenceGate] that has no escape hatches: whatever
 * [SchemaEquivalence] tolerates (field defaults, reflection hints) cannot change the wire format,
 * so anything that *does* change it lands here as a differing offset.
 */
internal object ByteEquivalence {
    private const val WINDOW = 12

    fun assertSameBytes(label: String, expected: ByteArray, actual: ByteArray) {
        val firstDifference = (0 until minOf(expected.size, actual.size)).firstOrNull { expected[it] != actual[it] }
        if (firstDifference == null && expected.size == actual.size) return

        val offset = firstDifference ?: minOf(expected.size, actual.size)
        throw AssertionError(
            buildString {
                append("[$label] encoding differs from avro4k's for the same logical data.\n")
                append("  avro4k  : ${expected.size} bytes\n")
                append("  $label : ${actual.size} bytes\n")
                if (firstDifference == null) {
                    append("  identical up to byte $offset, then one encoding ends\n")
                } else {
                    append("  first difference at byte $offset: ")
                    append("0x%02x != 0x%02x\n".format(expected[offset], actual[offset]))
                }
                append("  avro4k  [${window(expected, offset)}]: ${hex(expected, offset)}\n")
                append("  $label [${window(actual, offset)}]: ${hex(actual, offset)}")
            }
        )
    }

    private fun window(bytes: ByteArray, offset: Int): String {
        val from = maxOf(0, offset - WINDOW)
        val to = minOf(bytes.size, offset + WINDOW + 1)
        return "$from..${to - 1}"
    }

    private fun hex(bytes: ByteArray, offset: Int): String {
        val from = maxOf(0, offset - WINDOW)
        val to = minOf(bytes.size, offset + WINDOW + 1)
        return (from until to).joinToString(" ") { index ->
            val cell = "%02x".format(bytes[index])
            if (index == offset) ">$cell<" else cell
        }
    }
}
