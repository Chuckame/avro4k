package com.github.avrokotlin.benchmark.internal.apache

import com.github.avrokotlin.benchmark.internal.BadPartner as KotlinBadPartner
import com.github.avrokotlin.benchmark.internal.Client as KotlinClient
import com.github.avrokotlin.benchmark.internal.Clients as KotlinClients
import com.github.avrokotlin.benchmark.internal.EyeColor as KotlinEyeColor
import com.github.avrokotlin.benchmark.internal.GoodPartner as KotlinGoodPartner
import com.github.avrokotlin.benchmark.internal.Stranger as KotlinStranger
import org.apache.avro.reflect.AvroSchema
import org.apache.avro.reflect.Nullable
import org.apache.avro.reflect.Stringable
import org.apache.avro.reflect.Union
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Apache Avro's copy of the `complex` model, shaped for [org.apache.avro.reflect.ReflectData].
 *
 * It is the *same* model as [KotlinClients], field for field and type for type; only three things
 * differ, and each of them is forced by how `ReflectData` works:
 *
 * 1. **Mutable properties and a no-arg constructor.** `ReflectData` instantiates a record with its
 *    no-arg constructor and then populates it by reflective field writes. avro4k's model is an
 *    immutable `data class` with a single all-args constructor, which `ReflectData` cannot use.
 * 2. **Explicit `@AvroSchema` on two fields.** `ReflectData` infers `int` + `java-class:
 *    java.lang.Character` for a `Char`, where avro4k emits `int` + `logicalType: char`; and it
 *    erases `List<String?>` to `array of string`, losing the nullable element union. Both are
 *    pinned to avro4k's shape by hand. The `java-class` hints are kept inside those explicit
 *    schemas so `ReflectData` still materialises a `Char` / an `ArrayList`.
 * 3. **`@Union` on `partner`.** Kotlin sealed interfaces have no Avro equivalent; the branches and,
 *    crucially, their **order** are declared by hand to match avro4k's.
 *
 * Everything else — `Byte`/`Short` (encoded as `int` by both), `BigDecimal` as a stringable,
 * `ByteArray`, `Array<String>`, `LongArray`, `LocalDate`/`Instant` logical types — is left exactly
 * as avro4k has it. `ReflectData` reaches the same wire format for all of them once it is allowed
 * to use its own schema, which carries the `java-class` hints it needs to map the decoded values
 * back onto the right Java types. That equivalence is not assumed: it is asserted by
 * [com.github.avrokotlin.benchmark.internal.EquivalenceGate].
 */
internal class Clients {
    var clients: List<Client> = emptyList()
}

internal class Client {
    var id: Long = 0
    var index: Int = 0
    var isActive: Boolean = false

    /**
     * `@Stringable` rather than plain inference: without it `ReflectData` emits
     * `{"type":"string","java-class":"java.math.BigDecimal"}`, and reading that trips Avro's
     * `SERIALIZABLE_CLASSES` guard (`Forbidden java.math.BigDecimal!`). The annotation pins the
     * clean `"string"` avro4k also emits, and routes the value through the accessor's stringable
     * path — `toString()` on write, `new BigDecimal(String)` on read — which is exactly the work
     * avro4k's `@AvroStringable` does.
     */
    @field:Nullable
    @field:Stringable
    var balance: BigDecimal? = null

    @field:Nullable
    var picture: ByteArray? = null
    var age: Int = 0

    @field:Nullable
    var eyeColor: EyeColor? = null

    @field:Nullable
    var name: String? = null

    /**
     * `ReflectData` infers `{"type":"int","java-class":"java.lang.Character"}`; avro4k emits
     * `{"type":"int","logicalType":"char"}`. Both encode the code point as an `int`, so the schema
     * is pinned to avro4k's *and* keeps the `java-class` hint, which is what makes `ReflectData`
     * hand a `Character` back to this field on read.
     */
    @field:AvroSchema(CHAR_UNION_SCHEMA)
    var gender: Char? = null

    @field:Nullable
    var company: String? = null
    var emails: Array<String> = emptyArray()
    var phones: LongArray = LongArray(0)

    @field:Nullable
    var address: String? = null

    @field:Nullable
    var about: String? = null

    @field:Nullable
    var registered: LocalDate? = null
    var latitude: Double = 0.0
    var longitude: Float = 0f

    /** Kotlin erases `List<String?>` to `List<String>`, so the nullable element union is declared here. */
    @field:AvroSchema(NULLABLE_STRING_ARRAY_SCHEMA)
    var tags: List<String?> = emptyList()

    /** Branch order is part of the wire format: the union index is what is encoded. */
    @field:Union(Void::class, BadPartner::class, GoodPartner::class, Stranger::class)
    var partner: Partner? = null
    var map: Map<String, String> = emptyMap()
}

internal enum class EyeColor {
    BROWN,
    BLUE,
    GREEN,
}

internal interface Partner

internal class GoodPartner : Partner {
    var id: Long = 0
    var name: String = ""
    var since: Instant = Instant.EPOCH
}

internal class BadPartner : Partner {
    var id: Long = 0
    var name: String = ""
    var since: Instant = Instant.EPOCH
}

internal enum class Stranger : Partner {
    KNOWN_STRANGER,
    UNKNOWN_STRANGER,
}

private const val CHAR_UNION_SCHEMA =
    """["null",{"type":"int","logicalType":"char","java-class":"java.lang.Character"}]"""
private const val NULLABLE_STRING_ARRAY_SCHEMA =
    """{"type":"array","items":["null","string"],"java-class":"java.util.List"}"""

internal fun KotlinClients.toApache(): Clients = Clients().also { target ->
    target.clients = clients.map { it.toApache() }
}

private fun KotlinClient.toApache(): Client = Client().also { target ->
    target.id = id
    target.index = index
    target.isActive = isActive
    target.balance = balance
    target.picture = picture
    target.age = age
    target.eyeColor = eyeColor?.toApache()
    target.name = name
    target.gender = gender
    target.company = company
    target.emails = emails
    target.phones = phones
    target.address = address
    target.about = about
    target.registered = registered
    target.latitude = latitude
    target.longitude = longitude
    target.tags = tags
    target.partner = partner?.toApache()
    target.map = map
}

private fun KotlinEyeColor.toApache(): EyeColor = when (this) {
    KotlinEyeColor.BROWN -> EyeColor.BROWN
    KotlinEyeColor.BLUE -> EyeColor.BLUE
    KotlinEyeColor.GREEN -> EyeColor.GREEN
}

private fun com.github.avrokotlin.benchmark.internal.Partner.toApache(): Partner = when (this) {
    is KotlinGoodPartner -> GoodPartner().also {
        it.id = id
        it.name = name
        it.since = since
    }

    is KotlinBadPartner -> BadPartner().also {
        it.id = id
        it.name = name
        it.since = since
    }

    KotlinStranger.KNOWN_STRANGER -> Stranger.KNOWN_STRANGER
    KotlinStranger.UNKNOWN_STRANGER -> Stranger.UNKNOWN_STRANGER
}
