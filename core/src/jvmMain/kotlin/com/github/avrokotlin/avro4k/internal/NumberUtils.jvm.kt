package com.github.avrokotlin.avro4k.internal

import java.math.BigDecimal

internal fun BigDecimal.toByteExact(): Byte {
    if (this.toByte().toInt().toBigDecimal() != this) {
        return invalidTypeError()
    }
    return this.toByte()
}

internal fun BigDecimal.toShortExact(): Short {
    if (this.toShort().toInt().toBigDecimal() != this) {
        return invalidTypeError()
    }
    return this.toShort()
}

internal fun BigDecimal.toIntExact(): Int {
    if (this.toInt().toBigDecimal() != this) {
        return invalidTypeError()
    }
    return this.toInt()
}

internal fun BigDecimal.toLongExact(): Long {
    if (this.toLong().toBigDecimal() != this) {
        return invalidTypeError()
    }
    return this.toLong()
}

internal fun BigDecimal.toDoubleExact(): Double {
    if (this.toDouble().toBigDecimal() != this) {
        return invalidTypeError()
    }
    return this.toDouble()
}

internal fun BigDecimal.toFloatExact(): Float {
    if (this.toFloat().toBigDecimal() != this) {
        return invalidTypeError()
    }
    return this.toFloat()
}