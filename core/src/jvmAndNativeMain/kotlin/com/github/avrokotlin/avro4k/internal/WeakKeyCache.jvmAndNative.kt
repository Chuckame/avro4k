package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.InternalAvro4kApi

/**
 * The [CopyOnWriteWeakTable] in equality mode — the same code as [WeakIdentityKeyCache], matching keys with
 * `hashCode`/`equals` instead of identity. See there for the concurrency, visibility and reclamation argument.
 *
 * Replaces the former JVM-only `ConcurrentHashMap` + `WeakReference` + `ReferenceQueue` implementation, whose every
 * lookup allocated a `Key.Lookup` wrapper that escaped into `ConcurrentHashMap.get`.
 */
@InternalAvro4kApi
public actual class WeakKeyCache<K : Any, V : Any> actual constructor() : Cache<K, V> {
    private val table = CopyOnWriteWeakTable<K, V>(identity = false)

    actual override fun getOrPut(key: K, compute: () -> V): V = table.getOrPut(key, compute)

    public actual fun getOrNull(key: K): V? = table.getOrNull(key)

    public actual fun putIfAbsent(key: K, value: V): V? = table.putIfAbsent(key, value)

    public actual val size: Int get() = table.size
}