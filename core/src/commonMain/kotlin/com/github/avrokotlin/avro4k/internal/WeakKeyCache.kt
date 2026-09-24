package com.github.avrokotlin.avro4k.internal

import com.github.avrokotlin.avro4k.InternalAvro4kApi

/**
 * A [Cache] whose keys are matched by **equality** (`hashCode`/`equals`), for associating derived data with objects you
 * don't own. See [WeakIdentityKeyCache] for the identity-keyed counterpart, and [IdentityFirstCache] for an identity
 * fast path in front of this cache.
 *
 * Contract, on every platform:
 * - [getOrPut] never holds a lock while running `compute`, so `compute` may call back into the same cache. As the
 *   [Cache] contract allows, a value can be computed more than once for a key when threads race on a miss; one of the
 *   computed values wins and is returned by every later hit.
 * - A hit allocates nothing (no lookup wrapper; see [getOrNull] for the caller's lambda).
 * - **Only for a bounded set of keys** (bounded by equality: a key equal to a stored one hits rather than inserts).
 *   On JVM and native a put is O(capacity) (copy-on-write); on JS nothing is ever evicted.
 *
 * Per platform:
 * - **JVM and native:** the copy-on-write table shared with [WeakIdentityKeyCache], in equality mode. Keys are weakly
 *   referenced and stay collectable; stale entries (and their values) are dropped when the table is next rebuilt.
 * - **JS:** a plain, strong `HashMap`. JS is single-threaded, so no synchronization is needed, and a `WeakMap` cannot
 *   match by equality (it only compares identities). **Trade-off:** keys and values are held strongly for the lifetime
 *   of the cache, i.e. a leak bounded by the number of distinct keys ever inserted.
 *
 * Callers, and why each one's key set is bounded (M3-06):
 * - [IdentityFirstCache]'s equality layer (`Avro.schemaCache`, `RecordResolver`'s per-schema descriptor level,
 *   `PolymorphicResolver`): serial descriptors. Fresh-per-call descriptors are equal to the stored one, so the key set
 *   is the set of distinct serializable types an application uses.
 * - `RecordResolver.fieldCache` (outer level), `AnySerializer.decodingCache`, `AvroSingleObject.fingerprintCache`: writer
 *   schemas, a small set per application (equal re-parsed schemas hit the stored one). Identity-keyed from M3-11/M3-14.
 * - Confluent's `Serializers.cache`: registry schemas, bounded by the schema ids a consumer sees.
 */
@InternalAvro4kApi
public expect class WeakKeyCache<K : Any, V : Any>() : Cache<K, V> {
    override fun getOrPut(key: K, compute: () -> V): V

    /**
     * The value stored for a key equal to [key], or null. Never allocates. Hot call sites write
     * `cache.getOrNull(key) ?: cache.getOrPut(key) { … }` so that a capturing lambda only exists on a miss.
     */
    internal fun getOrNull(key: K): V?

    /**
     * Stores [value] for [key] unless an equal key is already present. Returns null if it was inserted — [key] is then the
     * instance the cache holds, its *canonical* key — or the existing value otherwise.
     */
    internal fun putIfAbsent(key: K, value: V): V?

    /** For tests: the number of entries (on JVM and native, including those whose key was collected but not swept yet). */
    internal val size: Int
}