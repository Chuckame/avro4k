package com.github.avrokotlin.avro4k.fixtures

internal actual fun <T> jvmAndNativeOrJs(
    jvmAndNative: T,
    js: T,
): T = jvmAndNative