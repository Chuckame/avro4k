package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.InternalAvro4kApi

/**
 * A [Cache] whose keys are matched by **identity** (`===`), never by `equals`, and are only weakly referenced: an
 * entry never keeps its key alive.
 *
 * It is meant for associating derived data with objects you don't own (schemas, serial descriptors), and for
 * **read-mostly** use: a hit must be cheap on every platform, while a miss may cost a copy of the whole cache.
 *
 * Contract, on every platform:
 * - Two keys that are `==` but not `===` are two different entries.
 * - [getOrPut] never holds a lock while running `compute`, so `compute` may call back into the same cache. As the
 *   [Cache] contract allows, a value can be computed more than once for a key when threads race on a miss; one of the
 *   computed values wins and is returned by every later hit.
 * - A value that strongly references its own key keeps that key alive, like in any weak-keyed map.
 *
 * The implementation is platform-specific; see each `actual` for its concurrency and reclamation argument.
 */
@InternalAvro4kApi
public expect class WeakIdentityKeyCache<K : Any, V : Any>() : Cache<K, V> {
    override fun getOrPut(key: K, compute: () -> V): V
}