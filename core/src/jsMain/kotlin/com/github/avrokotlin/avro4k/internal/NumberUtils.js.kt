package com.github.avrokotlin.avro4k.internal

// A Kotlin/JS `Float` is a JS number, and `Double.toFloat()` keeps the precision of a double. `Math.fround` gives the
// nearest 32-bit float, so the check rejects the same doubles as on the JVM and native.
internal actual fun Double.toFloatExact(): Float {
    if (fround(this) != this) {
        return invalidTypeError()
    }
    return this.toFloat()
}

@Suppress("UNUSED_PARAMETER")
private fun fround(value: Double): Double = js("Math.fround(value)")