package com.github.avrokotlin.avro4k.internal

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.identityHashCode
import kotlin.native.ref.WeakReference

@OptIn(ExperimentalNativeApi::class)
internal actual class WeakRef<T : Any> actual constructor(referent: T) {
    private val reference = WeakReference(referent)

    actual fun get(): T? = reference.value
}

@OptIn(ExperimentalNativeApi::class)
internal actual fun identityHashCode(value: Any): Int = value.identityHashCode()