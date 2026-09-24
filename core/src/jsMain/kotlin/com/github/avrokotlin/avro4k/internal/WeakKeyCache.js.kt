package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.InternalAvro4kApi

/**
 * A plain, **strong** `HashMap` (`hashCode`/`equals`).
 *
 * - **Why not weak:** a JS `WeakMap` matches keys by identity only, so it cannot implement an equality cache, and JS has
 *   no weak-keyed equality collection. A hand-made table of ES2021 `WeakRef`s (reached through `dynamic`, as the
 *   stdlib does not expose them) was not worth it for the bounded key sets below.
 * - **Why no synchronization:** JS is single-threaded.
 * - **Trade-off, accepted:** keys and values are never evicted, so the cache keeps them alive for its own lifetime (for
 *   the caches of an `Avro` instance, the instance's lifetime; `Avro.Default` lives forever). The leak is bounded by
 *   the number of *distinct* keys ever inserted, which is why every caller must feed a bounded key set (listed in the
 *   `expect` declaration's KDoc).
 */
@InternalAvro4kApi
public actual class WeakKeyCache<K : Any, V : Any> actual constructor() : Cache<K, V> {
    private val map = HashMap<K, V>()

    actual override fun getOrPut(key: K, compute: () -> V): V {
        getOrNull(key)?.let { return it }
        val value = compute()
        return putIfAbsent(key, value) ?: value
    }

    internal actual fun getOrNull(key: K): V? = map[key]

    internal actual fun putIfAbsent(key: K, value: V): V? {
        // Single-threaded: only a re-entrant computation of an equal key can have inserted it since the caller's miss.
        map[key]?.let { return it }
        map[key] = value
        return null
    }

    internal actual val size: Int get() = map.size
}