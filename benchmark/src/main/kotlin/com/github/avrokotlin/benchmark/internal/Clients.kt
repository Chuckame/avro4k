package com.github.avrokotlin.benchmark.internal

import com.github.avrokotlin.avro4k.AvroStringable
import com.github.avrokotlin.avro4k.serializer.InstantSerializer
import com.github.avrokotlin.avro4k.serializer.LocalDateSerializer
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * The `complex` model, and the one under test: this is avro4k's, and `Avro.schema<Clients>()` is the
 * canonical writer schema every other library is measured against. The other libraries keep their own
 * copies under `internal/apache` and `internal/jackson`, shaped for what each of them can round-trip;
 * nothing library-specific belongs here.
 */
@Serializable
internal data class Clients(
    val clients: List<Client>
)


@Serializable
internal data class Client(
    val id: Long,
    val index: Int,
    val isActive: Boolean,
    @Contextual
    @AvroStringable
    val balance: BigDecimal?,
    val picture: ByteArray?,
    val age: Int,
    val eyeColor: EyeColor?,
    val name: String?,
    val gender: Char?,
    val company: String?,
    val emails: Array<String>,
    val phones: LongArray,
    val address: String?,
    val about: String?,
    @Serializable(with = LocalDateSerializer::class)
    val registered: LocalDate?,
    val latitude: Double,
    val longitude: Float,
    val tags: List<String?>,
    val partner: Partner?,
    val map: Map<String, String>,
)

@Serializable
internal enum class EyeColor {
    BROWN,
    BLUE,
    GREEN;
}

@Serializable
internal sealed interface Partner

@Serializable
internal class GoodPartner(
    val id: Long,
    val name: String,
    @Serializable(with = InstantSerializer::class)
    val since: Instant
) : Partner

@Serializable
internal class BadPartner(
    val id: Long,
    val name: String,
    @Serializable(with = InstantSerializer::class)
    val since: Instant
) : Partner

@Serializable
internal enum class Stranger : Partner {
    KNOWN_STRANGER,
    UNKNOWN_STRANGER
}
