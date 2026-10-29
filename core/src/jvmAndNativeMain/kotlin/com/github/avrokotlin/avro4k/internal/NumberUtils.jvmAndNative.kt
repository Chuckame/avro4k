package com.github.avrokotlin.avro4k.internal

// The JVM and native round a float to 32 bits, so a round trip through a float tells whether the double is exact.
internal actual fun Double.toFloatExact(): Float {
    if (this.toFloat().toDouble() != this) {
        return invalidTypeError()
    }
    return this.toFloat()
}