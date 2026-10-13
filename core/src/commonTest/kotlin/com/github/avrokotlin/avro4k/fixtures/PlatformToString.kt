package com.github.avrokotlin.avro4k.fixtures

/**
 * [jvmAndNative] on the JVM and native platforms, [js] on Kotlin/JS: for the text of a `Float` or a `Double`, which each
 * platform writes with its own `toString()`, and so does avro4k for a floating-point number written as a string (`1.0`
 * is `"1.0"` on the JVM and native, `"1"` on Kotlin/JS).
 */
internal expect fun <T> jvmAndNativeOrJs(
    jvmAndNative: T,
    js: T,
): T