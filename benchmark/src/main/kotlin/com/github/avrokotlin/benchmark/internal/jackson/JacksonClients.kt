package com.github.avrokotlin.benchmark.internal.jackson

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JavaType
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.github.avrokotlin.benchmark.internal.EyeColor
import com.github.avrokotlin.benchmark.internal.Partner
import com.github.avrokotlin.benchmark.internal.Client as KotlinClient
import com.github.avrokotlin.benchmark.internal.Clients as KotlinClients
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Jackson's copy of the `complex` model.
 *
 * Jackson constructs every type in avro4k's model except [Partner]: it is a sealed interface, and
 * `complex.JacksonAvroBenchmark.read` failed with `Cannot construct instance of Partner (no
 * Creators…)` from the day it was written. Jackson-Avro has exactly two triggers for reading an Avro
 * union polymorphically (`AvroAnnotationIntrospector._findTypeResolver`): an
 * `org.apache.avro.reflect.@Union` annotation on the type, or a declared type of
 * `java.lang.Object`. Only the second one works here, so [Client.partner] is declared `Any?`; see
 * the property for the detail.
 *
 * This copy also gives the Jackson-only annotations — the `@JsonFormat` on the `BigDecimal` and the
 * `Char` serializer pair, which used to sit on avro4k's model — a home outside the model under test.
 * `simple` and `lists` need no copy: Jackson round-trips avro4k's own model for those, and its
 * generated schema for them matches the canonical one exactly.
 */
internal data class Clients(
    val clients: List<Client>,
)

internal data class Client(
    val id: Long,
    val index: Int,
    val isActive: Boolean,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    val balance: BigDecimal?,
    val picture: ByteArray?,
    val age: Int,
    val eyeColor: EyeColor?,
    val name: String?,
    @JsonSerialize(using = CharJacksonSerializer::class)
    @JsonDeserialize(using = CharJacksonDeserializer::class)
    val gender: Char?,
    val company: String?,
    val emails: Array<String>,
    val phones: LongArray,
    val address: String?,
    val about: String?,
    val registered: LocalDate?,
    val latitude: Double,
    val longitude: Float,
    val tags: List<String?>,
    /**
     * `Any?`, not `Partner?`.
     *
     * Declaring it `Partner?` and annotating the type with `org.apache.avro.reflect.@Union` (by
     * mix-in, so avro4k's model stays clean) *does* install Jackson's `AvroTypeResolverBuilder`, but
     * `AvroTypeDeserializer` then finds no native type id on the parser for the union branch and
     * falls back to the abstract base type — the same failure. The `java.lang.Object` route goes
     * through `AvroUntypedDeserializer`, which reads the branch's type id itself, resolves it as a
     * class name, and hands back a real `GoodPartner` / `BadPartner`.
     *
     * Two consequences, both verified rather than assumed by the equivalence gate:
     * - on write, `AvroWriteContext.resolveUnionIndex` picks the branch by the value's class name,
     *   so the branch classes must be avro4k's own — their full names are what the canonical schema
     *   declares. They are reused here rather than copied.
     * - on read, the `Stranger` **enum** branch comes back as its symbol `String` rather than as the
     *   enum constant. Re-encoding it reproduces avro4k's bytes exactly, so nothing is lost on the
     *   wire, but Jackson's decoded value for that one branch is less typed than avro4k's.
     */
    val partner: Any?,
    val map: Map<String, String>,
)

internal class CharJacksonSerializer : StdSerializer<Char>(Char::class.java) {
    override fun serialize(value: Char?, gen: JsonGenerator, provider: SerializerProvider) {
        value?.code?.let { gen.writeNumber(it) } ?: gen.writeNull()
    }

    override fun acceptJsonFormatVisitor(visitor: JsonFormatVisitorWrapper, typeHint: JavaType) {
        visitor.expectIntegerFormat(typeHint).numberType(JsonParser.NumberType.INT)
    }
}

internal class CharJacksonDeserializer : StdDeserializer<Char>(Char::class.java) {
    override fun deserialize(p0: JsonParser, p1: DeserializationContext): Char = p0.intValue.toChar()
}

internal fun KotlinClients.toJackson(): Clients = Clients(clients.map { it.toJackson() })

private fun KotlinClient.toJackson(): Client = Client(
    id = id,
    index = index,
    isActive = isActive,
    balance = balance,
    picture = picture,
    age = age,
    eyeColor = eyeColor,
    name = name,
    gender = gender,
    company = company,
    emails = emails,
    phones = phones,
    address = address,
    about = about,
    registered = registered,
    latitude = latitude,
    longitude = longitude,
    tags = tags,
    partner = partner,
    map = map,
)
