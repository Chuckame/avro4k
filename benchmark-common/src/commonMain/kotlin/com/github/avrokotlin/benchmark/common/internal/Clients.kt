package com.github.avrokotlin.benchmark.common.internal

import com.github.avrokotlin.avro4k.serializer.KotlinInstantSerializer
import kotlinx.serialization.Serializable
import kotlin.math.absoluteValue
import kotlin.time.Instant

/**
 * `benchmark/`'s `complex` model with common types in place of the JVM ones, each encoded as the same Avro type or one
 * of the same size:
 * - `balance`: the decimal as a [String], where `benchmark/` has a `BigDecimal` encoded as a string;
 * - `registered`: the days since the epoch as an [Int], where `benchmark/` has a `LocalDate` encoded as an int `date`;
 * - `since`: a [kotlin.time.Instant] encoded as a long `timestamp-millis`, as `benchmark/`'s `java.time.Instant`.
 */
@Serializable
data class Clients(
    val clients: List<Client>,
)

@Serializable
data class Client(
    val id: Long,
    val index: Int,
    val isActive: Boolean,
    val balance: String?,
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
    val registered: Int?,
    val latitude: Double,
    val longitude: Float,
    val tags: List<String?>,
    val partner: Partner?,
    val map: Map<String, String>,
)

@Serializable
enum class EyeColor {
    BROWN,
    BLUE,
    GREEN,
}

@Serializable
sealed interface Partner

@Serializable
class GoodPartner(
    val id: Long,
    val name: String,
    @Serializable(with = KotlinInstantSerializer::class)
    val since: Instant,
) : Partner

@Serializable
class BadPartner(
    val id: Long,
    val name: String,
    @Serializable(with = KotlinInstantSerializer::class)
    val since: Instant,
) : Partner

@Serializable
enum class Stranger : Partner {
    KNOWN_STRANGER,
    UNKNOWN_STRANGER,
}

/** `benchmark/`'s generator with the common types above: the same nulls, sizes and value ranges, not the same values. */
internal object ClientsGenerator {
    // 1900-01-01 and 2010-01-01, the range of `benchmark/`'s dates
    private const val FIRST_EPOCH_DAY = -25567
    private const val END_EPOCH_DAY = 14610

    fun generate(
        size: Int,
        random: RandomData,
    ) = Clients(
        List(size) { index ->
            Client(
                partner =
                    when (random.nextInt(4)) {
                        0 -> GoodPartner(random.nextLong(), random.alphabetic(30), Instant.fromEpochMilliseconds(random.nextLong()))
                        1 -> BadPartner(random.nextLong(), random.alphabetic(30), Instant.fromEpochMilliseconds(random.nextLong().absoluteValue))
                        2 -> if (random.nextBoolean()) Stranger.KNOWN_STRANGER else Stranger.UNKNOWN_STRANGER
                        else -> null
                    },
                id = random.nextLong().absoluteValue,
                index = random.nextInt(0, Int.MAX_VALUE),
                isActive = random.nextBoolean(),
                balance = if (index % 2 == 0) null else decimal(random),
                picture = if (index % 3 == 0) null else random.bytes(4048),
                age = random.nextInt(0, 100),
                eyeColor = if (index % 2 == 0) null else EyeColor.entries[random.nextInt(3)],
                name = if (index % 2 == 0) null else random.alphanumeric(20),
                gender = if (index % 2 == 0) null else if (random.nextBoolean()) 'M' else 'F',
                company = if (index % 2 == 0) null else random.alphanumeric(20),
                emails = Array(random.nextInt(5, 10)) { random.alphabetic(10) },
                phones = LongArray(random.nextInt(5, 10)) { random.nextInt().toLong().absoluteValue },
                address = if (index % 2 == 0) null else random.alphanumeric(20),
                about = random.alphanumeric(20),
                registered = if (index % 3 == 0) null else random.nextInt(FIRST_EPOCH_DAY, END_EPOCH_DAY),
                latitude = random.nextDouble(0.0, 90.0),
                longitude = random.nextFloat(180),
                tags = List(random.nextInt(5, 25)) { if (it % 2 == 0) null else random.alphanumeric(10) },
                map = buildMap { repeat(10) { put(random.alphanumeric(10), random.alphanumeric(10)) } },
            )
        }
    )

    /** A decimal in `[0, 1000)` with 4 digits after the point, as `benchmark/`'s `BigDecimal`s */
    private fun decimal(random: RandomData): String = "${random.nextInt(1000)}.${random.nextInt(10000).toString().padStart(4, '0')}"
}
