package com.github.avrokotlin.avro4k.internal

import kotlinx.serialization.SerializationException

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

internal fun Double.toFloatExact(): Float {
    if (this.toFloat().toDouble() != this) {
        return invalidTypeError()
    }
    return this.toFloat()
}

internal inline fun <reified T> Any.invalidTypeError(): T {
    throw SerializationException("Value $this is not a valid ${T::class.simpleName}")
}