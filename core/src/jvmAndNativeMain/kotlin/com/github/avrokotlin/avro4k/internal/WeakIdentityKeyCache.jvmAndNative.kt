package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.InternalAvro4kApi

/**
 * The [CopyOnWriteWeakTable] in identity mode; see there for the concurrency, visibility and reclamation argument, and
 * for why the table is hand-written and shared by the JVM and native.
 */
@InternalAvro4kApi
public actual class WeakIdentityKeyCache<K : Any, V : Any> actual constructor() : Cache<K, V> {
    private val table = CopyOnWriteWeakTable<K, V>(identity = true)

    actual override fun getOrPut(key: K, compute: () -> V): V = table.getOrPut(key, compute)

    internal actual fun getOrNull(key: K): V? = table.getOrNull(key)

    internal actual fun putIfAbsent(key: K, value: V): V? = table.putIfAbsent(key, value)

    internal actual val size: Int get() = table.size
}