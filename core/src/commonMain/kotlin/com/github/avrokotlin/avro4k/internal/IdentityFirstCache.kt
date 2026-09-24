package com.github.avrokotlin.avro4k.internal

/**
 * A cache for keys that are *usually* the same instance but may be equal-but-distinct ones — serial descriptors. An
 * **identity** layer ([WeakIdentityKeyCache]) answers repeated lookups of the same instance with reference comparisons
 * only; an **equality** layer ([WeakKeyCache]) behind it is the source of truth, and keeps equal-but-distinct instances
 * mapping to one value.
 *
 * Why both (`docs/plans/notes/b6.md`): generated serializers of plain classes, enums and objects are singletons, so
 * their descriptors are stable instances; but `serializer<List<Foo>>()`, `ListSerializer(…)`, `MapSerializer(…)`,
 * nullable and generic-class serializers return a **fresh** descriptor on every call, equal (same hash) to the previous
 * one. Identity alone would recompute, and hand out a fresh value, on every call for those.
 *
 * ## Lookup order
 * 1. Identity hit → return. No `hashCode`, no `equals`, no allocation.
 * 2. Equality hit → return. `hashCode` + `equals`, no allocation. **The key is not remembered by identity.**
 * 3. Miss → compute (outside of any lock), insert into the equality layer, and seed the identity layer with this very
 *    instance **only if the equality insertion succeeded**, i.e. only if this instance is now the equality layer's
 *    canonical key. After a lost race the winner's value is returned and nothing is seeded.
 *
 * ## Seeding policy, and its consequence
 * The identity layer only ever holds *canonical* instances: at most one per equality class that is live. Seeding every
 * instance that missed the identity layer would instead add one entry per fresh descriptor — per top-level call for
 * `serializer<List<Foo>>()` — growing the identity layer without bound (until GC) and paying its O(capacity)
 * copy-on-write put on **every** call. So a fresh-per-call key always pays the equality lookup (step 2), which is exactly
 * the cost of the pre-M3 equality-only cache minus its per-lookup wrapper allocation — never more. A stable instance
 * pays it too while an equal fresh instance is the canonical key; once that one is collected, the next lookup misses,
 * recomputes (as the equality-only cache always did after a collection), and the stable instance becomes canonical.
 *
 * ## Contract
 * As for [Cache]: a value can be recomputed at any time (a lost race, a collected key), and `compute` may call back into
 * this cache. [getOrPut] is `inline` so that call sites do not allocate a capturing lambda per lookup; that is why this
 * class does not implement [Cache]. Both layers are CAS-based or single-threaded (JS), so an instance may be shared by
 * all threads.
 */
internal class IdentityFirstCache<K : Any, V : Any> {
    internal val identity = WeakIdentityKeyCache<K, V>()
    internal val equality = WeakKeyCache<K, V>()

    inline fun getOrPut(
        key: K,
        compute: () -> V,
    ): V {
        identity.getOrNull(key)?.let { return it }
        equality.getOrNull(key)?.let { return it }
        return putComputed(key, compute())
    }

    /** Miss path of [getOrPut]: see the class KDoc for the seeding rule. */
    fun putComputed(
        key: K,
        value: V,
    ): V {
        // Non-null: an equal key was inserted meanwhile (a racing thread, or a re-entrant compute). `key` is not the
        // canonical instance, so it must not be seeded.
        equality.putIfAbsent(key, value)?.let { return it }
        return identity.putIfAbsent(key, value) ?: value
    }

    /** For tests: see [WeakIdentityKeyCache.size]. */
    internal val identitySize: Int get() = identity.size

    /** For tests: see [WeakKeyCache.size]. */
    internal val equalitySize: Int get() = equality.size
}