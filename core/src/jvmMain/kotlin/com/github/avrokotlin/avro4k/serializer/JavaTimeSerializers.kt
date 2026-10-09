package com.github.avrokotlin.avro4k.serializer

import com.github.avrokotlin.avro4k.AvroDecoder
import com.github.avrokotlin.avro4k.AvroEncoder
import com.github.avrokotlin.avro4k.AvroSchema
import com.github.avrokotlin.avro4k.internal.UnexpectedDecodeSchemaError
import com.github.avrokotlin.avro4k.internal.schema.isUnion
import com.github.avrokotlin.avro4k.internal.schema.knownLogicalTypeName
import com.github.avrokotlin.avro4k.logicalTypeMismatchError
import com.github.avrokotlin.avro4k.trySelectTypeNameFromUnion
import com.github.avrokotlin.avro4k.unsupportedWriterTypeError
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Period
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal val JavaTimeSerializersModule: SerializersModule get() =
    SerializersModule {
        contextual(LocalDateSerializer)
        contextual(LocalTimeSerializer)
        contextual(LocalDateTimeSerializer)
        contextual(InstantSerializer)
        contextual(JavaDurationSerializer)
        contextual(JavaPeriodSerializer)
    }

public object LocalDateSerializer : AvroSerializer<LocalDate>(LocalDate::class.qualifiedName!!) {
    override fun getSchema(context: SchemaSupplierContext): AvroSchema {
        return context.inlinedElements.firstNotNullOfOrNull {
            it.stringable?.createSchema()
        } ?: logicalTypeSchema(AvroSchema.Type.INT, LOGICAL_TYPE_NAME_DATE)
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: LocalDate,
    ) {
        with(encoder) {
            if (currentWriterSchema.isUnion) {
                trySelectTypeNameFromUnion(AvroSchema.Type.INT) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                    throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.STRING)
            }
            when (currentWriterSchema.type) {
                AvroSchema.Type.INT ->
                    when (currentWriterSchema.knownLogicalTypeName) {
                        LOGICAL_TYPE_NAME_DATE -> encodeInt(value.toEpochDay().toInt())
                        else -> throw logicalTypeMismatchError(LOGICAL_TYPE_NAME_DATE, AvroSchema.Type.INT)
                    }

                AvroSchema.Type.STRING -> encodeString(value.toString())

                else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.STRING)
            }
        }
    }

    override fun serializeGeneric(
        encoder: Encoder,
        value: LocalDate,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeAvro(decoder: AvroDecoder): LocalDate {
        val schema = decoder.currentWriterSchema
        return when (schema.type) {
            AvroSchema.Type.INT ->
                when (schema.knownLogicalTypeName) {
                    LOGICAL_TYPE_NAME_DATE, null -> LocalDate.ofEpochDay(decoder.decodeInt().toLong())
                    else -> throw decoder.localDateSchemaError()
                }

            AvroSchema.Type.LONG ->
                when (schema.knownLogicalTypeName) {
                    null -> LocalDate.ofEpochDay(decoder.decodeLong())
                    else -> throw decoder.localDateSchemaError()
                }

            AvroSchema.Type.STRING -> LocalDate.parse(decoder.decodeString())

            else -> throw decoder.localDateSchemaError()
        }
    }

    private fun AvroDecoder.localDateSchemaError() = UnexpectedDecodeSchemaError("LocalDate", AvroSchema.Type.INT, AvroSchema.Type.LONG)

    override fun deserializeGeneric(decoder: Decoder): LocalDate {
        return LocalDate.parse(decoder.decodeString())
    }
}

private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val NANOS_PER_MICROSECOND = 1_000L

public object LocalTimeSerializer : AvroSerializer<LocalTime>(LocalTime::class.qualifiedName!!) {
    override fun getSchema(context: SchemaSupplierContext): AvroSchema {
        return context.inlinedElements.firstNotNullOfOrNull {
            it.stringable?.createSchema()
        } ?: logicalTypeSchema(AvroSchema.Type.INT, LOGICAL_TYPE_NAME_TIME_MILLIS)
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: LocalTime,
    ) {
        with(encoder) {
            if (currentWriterSchema.isUnion) {
                trySelectTypeNameFromUnion(AvroSchema.Type.INT) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.LONG) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                    throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.STRING)
            }
            when (currentWriterSchema.type) {
                AvroSchema.Type.INT ->
                    when (currentWriterSchema.knownLogicalTypeName) {
                        LOGICAL_TYPE_NAME_TIME_MILLIS -> encodeInt(value.toMillisOfDay())
                        else -> throw logicalTypeMismatchError(LOGICAL_TYPE_NAME_TIME_MILLIS, AvroSchema.Type.INT)
                    }

                AvroSchema.Type.LONG ->
                    when (currentWriterSchema.knownLogicalTypeName) {
                        LOGICAL_TYPE_NAME_TIME_MICROS -> encodeLong(value.toMicroOfDay())
                        else -> throw logicalTypeMismatchError(LOGICAL_TYPE_NAME_TIME_MICROS, AvroSchema.Type.LONG)
                    }

                AvroSchema.Type.STRING -> encodeString(value.toString())

                else -> throw unsupportedWriterTypeError(AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.STRING)
            }
        }
    }

    override fun serializeGeneric(
        encoder: Encoder,
        value: LocalTime,
    ) {
        encoder.encodeString(value.toString())
    }

    private fun LocalTime.toMillisOfDay() = (toNanoOfDay() / NANOS_PER_MILLISECOND).toInt()

    private fun LocalTime.toMicroOfDay() = toNanoOfDay() / NANOS_PER_MICROSECOND

    override fun deserializeAvro(decoder: AvroDecoder): LocalTime {
        val schema = decoder.currentWriterSchema
        return when (schema.type) {
            AvroSchema.Type.INT ->
                when (schema.knownLogicalTypeName) {
                    LOGICAL_TYPE_NAME_TIME_MILLIS, null -> LocalTime.ofNanoOfDay(decoder.decodeInt() * NANOS_PER_MILLISECOND)
                    else -> throw decoder.localTimeSchemaError()
                }

            AvroSchema.Type.LONG ->
                when (schema.knownLogicalTypeName) {
                    null -> LocalTime.ofNanoOfDay(decoder.decodeLong() * NANOS_PER_MILLISECOND)
                    LOGICAL_TYPE_NAME_TIME_MICROS -> LocalTime.ofNanoOfDay(decoder.decodeLong() * NANOS_PER_MICROSECOND)
                    else -> throw decoder.localTimeSchemaError()
                }

            AvroSchema.Type.STRING -> LocalTime.parse(decoder.decodeString())

            else -> throw decoder.localTimeSchemaError()
        }
    }

    private fun AvroDecoder.localTimeSchemaError() =
        UnexpectedDecodeSchemaError("LocalTime", AvroSchema.Type.INT, AvroSchema.Type.LONG, AvroSchema.Type.STRING)

    override fun deserializeGeneric(decoder: Decoder): LocalTime {
        return LocalTime.parse(decoder.decodeString())
    }
}

public object LocalDateTimeSerializer : AvroSerializer<LocalDateTime>(LocalDateTime::class.qualifiedName!!) {
    override fun getSchema(context: SchemaSupplierContext): AvroSchema {
        return context.inlinedElements.firstNotNullOfOrNull {
            it.stringable?.createSchema()
        } ?: logicalTypeSchema(AvroSchema.Type.LONG, LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS)
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: LocalDateTime,
    ) {
        with(encoder) {
            if (currentWriterSchema.isUnion) {
                trySelectTypeNameFromUnion(AvroSchema.Type.LONG) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                    throw unsupportedWriterTypeError(AvroSchema.Type.LONG, AvroSchema.Type.STRING)
            }
            when (currentWriterSchema.type) {
                AvroSchema.Type.LONG ->
                    when (currentWriterSchema.knownLogicalTypeName) {
                        LOGICAL_TYPE_NAME_TIMESTAMP_MICROS -> encodeLong(value.toInstant(ZoneOffset.UTC).toEpochMicros())
                        LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS -> encodeLong(value.toInstant(ZoneOffset.UTC).toEpochMilli())
                        else -> throw logicalTypeMismatchError(LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS, AvroSchema.Type.LONG)
                    }

                AvroSchema.Type.STRING -> encodeString(value.toString())

                else -> throw unsupportedWriterTypeError(AvroSchema.Type.LONG, AvroSchema.Type.STRING)
            }
        }
    }

    override fun serializeGeneric(
        encoder: Encoder,
        value: LocalDateTime,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeAvro(decoder: AvroDecoder): LocalDateTime {
        val schema = decoder.currentWriterSchema
        return when (schema.type) {
            AvroSchema.Type.LONG ->
                when (schema.knownLogicalTypeName) {
                    LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS, null -> LocalDateTime.ofInstant(Instant.ofEpochMilli(decoder.decodeLong()), ZoneOffset.UTC)
                    LOGICAL_TYPE_NAME_TIMESTAMP_MICROS -> LocalDateTime.ofInstant(Instant.EPOCH.plus(decoder.decodeLong(), ChronoUnit.MICROS), ZoneOffset.UTC)
                    else -> throw decoder.UnexpectedDecodeSchemaError("Instant", AvroSchema.Type.LONG)
                }

            AvroSchema.Type.STRING -> LocalDateTime.parse(decoder.decodeString())

            else -> throw decoder.UnexpectedDecodeSchemaError("Instant", AvroSchema.Type.LONG)
        }
    }

    override fun deserializeGeneric(decoder: Decoder): LocalDateTime {
        return LocalDateTime.parse(decoder.decodeString())
    }
}

public object InstantSerializer : AvroSerializer<Instant>(Instant::class.qualifiedName!!) {
    override fun getSchema(context: SchemaSupplierContext): AvroSchema {
        return context.inlinedElements.firstNotNullOfOrNull {
            it.stringable?.createSchema()
        } ?: logicalTypeSchema(AvroSchema.Type.LONG, LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS)
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: Instant,
    ) {
        with(encoder) {
            if (currentWriterSchema.isUnion) {
                trySelectTypeNameFromUnion(AvroSchema.Type.LONG) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                    throw unsupportedWriterTypeError(AvroSchema.Type.LONG, AvroSchema.Type.STRING)
            }
            when (currentWriterSchema.type) {
                AvroSchema.Type.LONG ->
                    when (currentWriterSchema.knownLogicalTypeName) {
                        LOGICAL_TYPE_NAME_TIMESTAMP_MICROS -> encodeLong(value.toEpochMicros())
                        LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS -> encodeLong(value.toEpochMilli())
                        else -> throw logicalTypeMismatchError(LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS, AvroSchema.Type.LONG)
                    }

                AvroSchema.Type.STRING -> encodeString(value.toString())

                else -> throw unsupportedWriterTypeError(AvroSchema.Type.LONG, AvroSchema.Type.STRING)
            }
        }
    }

    override fun serializeGeneric(
        encoder: Encoder,
        value: Instant,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeAvro(decoder: AvroDecoder): Instant {
        val schema = decoder.currentWriterSchema
        return when (schema.type) {
            AvroSchema.Type.LONG ->
                when (schema.knownLogicalTypeName) {
                    LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS, null -> Instant.ofEpochMilli(decoder.decodeLong())
                    LOGICAL_TYPE_NAME_TIMESTAMP_MICROS -> Instant.EPOCH.plus(decoder.decodeLong(), ChronoUnit.MICROS)
                    else -> throw decoder.UnexpectedDecodeSchemaError("Instant", AvroSchema.Type.LONG)
                }

            AvroSchema.Type.STRING -> Instant.parse(decoder.decodeString())

            else -> throw decoder.UnexpectedDecodeSchemaError("Instant", AvroSchema.Type.LONG)
        }
    }

    override fun deserializeGeneric(decoder: Decoder): Instant {
        return Instant.parse(decoder.decodeString())
    }
}

public object InstantToMicroSerializer : AvroSerializer<Instant>(Instant::class.qualifiedName!!) {
    override fun getSchema(context: SchemaSupplierContext): AvroSchema {
        return context.inlinedElements.firstNotNullOfOrNull {
            it.stringable?.createSchema()
        } ?: logicalTypeSchema(AvroSchema.Type.LONG, LOGICAL_TYPE_NAME_TIMESTAMP_MICROS)
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: Instant,
    ) {
        with(encoder) {
            if (currentWriterSchema.isUnion) {
                trySelectTypeNameFromUnion(AvroSchema.Type.LONG) ||
                    trySelectTypeNameFromUnion(AvroSchema.Type.STRING) ||
                    throw unsupportedWriterTypeError(AvroSchema.Type.LONG, AvroSchema.Type.STRING)
            }
            when (currentWriterSchema.type) {
                AvroSchema.Type.LONG ->
                    when (currentWriterSchema.knownLogicalTypeName) {
                        LOGICAL_TYPE_NAME_TIMESTAMP_MICROS -> encodeLong(value.toEpochMicros())
                        LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS -> encodeLong(value.toEpochMilli())
                        else -> throw logicalTypeMismatchError(LOGICAL_TYPE_NAME_TIMESTAMP_MICROS, AvroSchema.Type.LONG)
                    }

                AvroSchema.Type.STRING -> encodeString(value.toString())

                else -> throw unsupportedWriterTypeError(AvroSchema.Type.LONG, AvroSchema.Type.STRING)
            }
        }
    }

    override fun serializeGeneric(
        encoder: Encoder,
        value: Instant,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeAvro(decoder: AvroDecoder): Instant {
        val schema = decoder.currentWriterSchema
        return when (schema.type) {
            AvroSchema.Type.LONG ->
                when (schema.knownLogicalTypeName) {
                    LOGICAL_TYPE_NAME_TIMESTAMP_MICROS, null -> Instant.EPOCH.plus(decoder.decodeLong(), ChronoUnit.MICROS)
                    LOGICAL_TYPE_NAME_TIMESTAMP_MILLIS -> Instant.ofEpochMilli(decoder.decodeLong())
                    else -> throw decoder.instantToMicroSchemaError()
                }

            AvroSchema.Type.STRING -> Instant.parse(decoder.decodeString())

            else -> throw decoder.instantToMicroSchemaError()
        }
    }

    private fun AvroDecoder.instantToMicroSchemaError() = UnexpectedDecodeSchemaError("Instant", AvroSchema.Type.LONG, AvroSchema.Type.STRING)

    override fun deserializeGeneric(decoder: Decoder): Instant {
        return Instant.parse(decoder.decodeString())
    }
}

private fun Instant.toEpochMicros() = ChronoUnit.MICROS.between(Instant.EPOCH, this)

/**
 * Serializes an [Duration] as a fixed logical type of `duration`.
 *
 * [avro spec](https://avro.apache.org/docs/1.12.1/specification/#duration)
 */
public object JavaDurationSerializer : AvroSerializer<Duration>(Duration::class.qualifiedName!!) {
    override fun getSchema(context: SchemaSupplierContext): AvroSchema {
        return context.inlinedElements.firstNotNullOfOrNull {
            it.stringable?.createSchema()
        } ?: AvroDurationSerializer.DURATION_SCHEMA
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: Duration,
    ) {
        AvroDurationSerializer.serializeAvro(encoder, value.toAvroDuration())
    }

    override fun deserializeAvro(decoder: AvroDecoder): Duration {
        return AvroDurationSerializer.deserializeAvro(decoder).toJavaDuration()
    }

    override fun serializeGeneric(
        encoder: Encoder,
        value: Duration,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeGeneric(decoder: Decoder): Duration {
        return Duration.parse(decoder.decodeString())
    }

    private fun AvroDuration.toJavaDuration(): Duration {
        if (months != 0u) {
            throw SerializationException("java.time.Duration cannot contains months")
        }
        return Duration.ofMillis(days.toLong() * MILLIS_PER_DAY + millis.toLong())
    }

    private fun Duration.toAvroDuration(): AvroDuration {
        if (isNegative) {
            throw SerializationException("${Duration::class.qualifiedName} cannot be converted to ${AvroDuration::class.qualifiedName} as it cannot be negative")
        }
        val millis = this.toMillis()
        return AvroDuration(
            months = 0u,
            days = (millis / MILLIS_PER_DAY).toUInt(),
            millis = (millis % MILLIS_PER_DAY).toUInt()
        )
    }
}

/**
 * Serializes an [Period] as a fixed logical type of `duration`.
 *
 * [avro spec](https://avro.apache.org/docs/1.12.1/specification/#duration)
 */
public object JavaPeriodSerializer : AvroSerializer<Period>(Period::class.qualifiedName!!) {
    override fun getSchema(context: SchemaSupplierContext): AvroSchema {
        return context.inlinedElements.firstNotNullOfOrNull {
            it.stringable?.createSchema()
        } ?: AvroDurationSerializer.DURATION_SCHEMA
    }

    override fun serializeAvro(
        encoder: AvroEncoder,
        value: Period,
    ) {
        AvroDurationSerializer.serializeAvro(encoder, value.toAvroDuration())
    }

    override fun deserializeAvro(decoder: AvroDecoder): Period {
        return AvroDurationSerializer.deserializeAvro(decoder).toJavaPeriod()
    }

    override fun serializeGeneric(
        encoder: Encoder,
        value: Period,
    ) {
        encoder.encodeString(value.toString())
    }

    override fun deserializeGeneric(decoder: Decoder): Period {
        return Period.parse(decoder.decodeString())
    }

    private fun AvroDuration.toJavaPeriod(): Period {
        val years = (months / 12u).toInt()
        val months = (months % 12u).toInt()
        val days = days.toInt() + (millis.toLong() / MILLIS_PER_DAY).toInt()
        // Ignore the remaining millis as Period does not support less than a day

        return Period.of(years, months, days).also {
            if (it.isNegative) {
                throw SerializationException("java.time.Period overflow from $this")
            }
        }
    }

    private fun Period.toAvroDuration(): AvroDuration {
        return AvroDuration(
            months = (years * DAYS_PER_YEAR + months).toUInt(),
            days = days.toUInt(),
            millis = 0u
        )
    }
}

private const val MILLIS_PER_DAY = 1000 * 60 * 60 * 24
private const val DAYS_PER_YEAR = 12