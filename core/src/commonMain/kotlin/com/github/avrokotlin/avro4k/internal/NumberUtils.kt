package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.AvroSchemaMismatchException

internal fun Int.toByteExact(): Byte {
    if (this.toByte().toInt() != this) {
        return invalidTypeError()
    }
    return this.toByte()
}

internal fun Long.toByteExact(): Byte {
    if (this.toByte().toLong() != this) {
        return invalidTypeError()
    }
    return this.toByte()
}

internal fun Int.toShortExact(): Short {
    if (this.toShort().toInt() != this) {
        return invalidTypeError()
    }
    return this.toShort()
}

internal fun Long.toShortExact(): Short {
    if (this.toShort().toLong() != this) {
        return invalidTypeError()
    }
    return this.toShort()
}

internal fun Long.toIntExact(): Int {
    if (this.toInt().toLong() != this) {
        return invalidTypeError()
    }
    return this.toInt()
}

/**
 * This double as a float, or an [AvroSchemaMismatchException] when no float holds exactly the same number. NaN is
 * rejected too, as it is never equal to itself.
 *
 * Declared per platform because Kotlin/JS stores a `Float` as a JS number and its `Double.toFloat()` does not round to
 * 32 bits, so the check "the double survives a round trip through a float" needs a rounding that JS honours.
 */
internal expect fun Double.toFloatExact(): Float

internal inline fun <reified T> Any.invalidTypeError(): T {
    throw AvroSchemaMismatchException("Value $this is not a valid ${T::class.simpleName}")
}