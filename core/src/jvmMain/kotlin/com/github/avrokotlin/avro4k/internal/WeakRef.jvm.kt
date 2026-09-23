package com.github.avrokotlin.avro4k.internal

import java.lang.ref.WeakReference

internal actual class WeakRef<T : Any> actual constructor(referent: T) : WeakReference<T>(referent) {
    actual override fun get(): T? = super.get()
}

internal actual fun identityHashCode(value: Any): Int = System.identityHashCode(value)