package com.github.avrokotlin.benchmark.micro

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * Models for the micro-benchmarks in this package.
 *
 * Every model here exists to put exactly one avro4k internal under load and as little else as
 * possible, so that a change in `gc.alloc.rate.norm` can be attributed to that internal. They are
 * deliberately *not* shared with the end-to-end workloads in `internal/`, which model realistic
 * payloads instead.
 *
 * All the data is generated from a fixed seed so that two runs encode byte-identical payloads.
 */
private const val SEED = 20260919L

private const val ALPHANUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

private fun Random.randomString(length: Int): String = String(CharArray(length) { ALPHANUM[nextInt(ALPHANUM.length)] })

// ---------------------------------------------------------------------------
// Deeply nested records, one field each.
//
// Isolates the per-structure cost: one encoder/decoder object allocated per `beginStructure`
// (see AbstractAvroDirectDecoder.beginStructure) and one RecordResolver.resolveFields lookup per
// record *instance*. Each level carries a single field so the payload stays ~1 byte and the
// measurement is dominated by the structure handling rather than by the data.
// ---------------------------------------------------------------------------
@Serializable
internal data class Nested1(val value: Long)

@Serializable
internal data class Nested2(val child: Nested1)

@Serializable
internal data class Nested3(val child: Nested2)

@Serializable
internal data class Nested4(val child: Nested3)

@Serializable
internal data class Nested5(val child: Nested4)

@Serializable
internal data class Nested6(val child: Nested5)

@Serializable
internal data class Nested7(val child: Nested6)

@Serializable
internal data class Nested8(val child: Nested7)

internal object NestedRecords {
    val depth1: Nested1 = Nested1(1L)
    val depth3: Nested3 = Nested3(Nested2(Nested1(3L)))
    val depth8: Nested8 = Nested8(Nested7(Nested6(Nested5(Nested4(Nested3(Nested2(Nested1(8L))))))))
}

// ---------------------------------------------------------------------------
// Collections.
//
// Isolates the collection-serializer wrapper allocated per collection and the array block
// decoding. `LongList` is the cheapest possible element type (no nested structure at all), while
// `SmallRecordList` adds one begin/endStructure per element on top of the very same array
// handling, so the difference between the two attributes the per-element structure cost.
// ---------------------------------------------------------------------------
@Serializable
internal data class LongList(val values: List<Long>)

@Serializable
internal data class SmallRecord(val id: Long, val flag: Boolean)

@Serializable
internal data class SmallRecordList(val values: List<SmallRecord>)

internal fun longList(size: Int): LongList {
    val random = Random(SEED)
    return LongList(List(size) { random.nextLong() })
}

internal fun smallRecordList(size: Int): SmallRecordList {
    val random = Random(SEED)
    return SmallRecordList(List(size) { SmallRecord(random.nextLong(), random.nextBoolean()) })
}

// ---------------------------------------------------------------------------
// String field storms.
//
// `NullableStringStorm` makes every field a `["null","string"]` union, so every single encoded
// field goes through the union branch resolution chain of AbstractAvroEncoder.encodeString (up to
// 9 `trySelect*` attempts). `StringStorm` is the exact same record with non-union fields, so the
// difference between the two is the union resolution, and `StringStorm` alone is the plain
// string encode/decode cost.
// ---------------------------------------------------------------------------
internal const val STRING_STORM_FIELDS = 12
internal const val STRING_STORM_LENGTH = 16

@Serializable
internal data class StringStorm(
    val f0: String,
    val f1: String,
    val f2: String,
    val f3: String,
    val f4: String,
    val f5: String,
    val f6: String,
    val f7: String,
    val f8: String,
    val f9: String,
    val f10: String,
    val f11: String,
)

@Serializable
internal data class NullableStringStorm(
    val f0: String?,
    val f1: String?,
    val f2: String?,
    val f3: String?,
    val f4: String?,
    val f5: String?,
    val f6: String?,
    val f7: String?,
    val f8: String?,
    val f9: String?,
    val f10: String?,
    val f11: String?,
)

internal fun stringStorm(): StringStorm {
    val random = Random(SEED)
    return StringStorm(
        random.randomString(STRING_STORM_LENGTH),
        random.randomString(STRING_STORM_LENGTH),
        random.randomString(STRING_STORM_LENGTH),
        random.randomString(STRING_STORM_LENGTH),
        random.randomString(STRING_STORM_LENGTH),
        random.randomString(STRING_STORM_LENGTH),
        random.randomString(STRING_STORM_LENGTH),
        random.randomString(STRING_STORM_LENGTH),
        random.randomString(STRING_STORM_LENGTH),
        random.randomString(STRING_STORM_LENGTH),
        random.randomString(STRING_STORM_LENGTH),
        random.randomString(STRING_STORM_LENGTH),
    )
}

/**
 * Every field is non-null on purpose: a null would short-circuit on the very first
 * `trySelectTypeNameFromUnion(NULL)` and would measure the null branch instead of the string one.
 */
internal fun nullableStringStorm(): NullableStringStorm {
    val s = stringStorm()
    return NullableStringStorm(s.f0, s.f1, s.f2, s.f3, s.f4, s.f5, s.f6, s.f7, s.f8, s.f9, s.f10, s.f11)
}

// ---------------------------------------------------------------------------
// Sealed interface storms.
//
// Isolates the polymorphic resolve cost: a `Pair` plus a cache lookup per polymorphic value
// (PolymorphicResolver.getFullNamesAndAliasesToSerialName), plus the extra encoder/decoder layer.
// `CircleList` is the monomorphic control: same element payload, same array handling, no
// polymorphism.
// ---------------------------------------------------------------------------
@Serializable
internal sealed interface Shape

@Serializable
internal data class Circle(val radius: Double) : Shape

@Serializable
internal data class Square(val side: Double) : Shape

@Serializable
internal data class Rectangle(val width: Double, val height: Double) : Shape

@Serializable
internal data class ShapeList(val shapes: List<Shape>)

@Serializable
internal data class CircleList(val shapes: List<Circle>)

/**
 * All the elements are [Circle]s so that the polymorphic and the monomorphic variants encode the
 * exact same payload apart from the union index, and the difference is only the resolution.
 */
internal fun shapeList(size: Int): ShapeList {
    val random = Random(SEED)
    return ShapeList(List(size) { Circle(random.nextDouble()) })
}

internal fun circleList(size: Int): CircleList {
    val random = Random(SEED)
    return CircleList(List(size) { Circle(random.nextDouble()) })
}

// ---------------------------------------------------------------------------
// Field order mismatch.
//
// Isolates ReorderingCompositeEncoder, which is only instantiated when the descriptor's element
// order is not contiguous in the writer schema (EncodingWorkflow.NonContiguous). It buffers every
// field as a capturing lambda, so the cost is expected to scale with the field count.
// ---------------------------------------------------------------------------
@Serializable
internal data class FieldOrderRecord(
    val a: Long,
    val b: Long,
    val c: Long,
    val d: Long,
    val e: Long,
    val f: Long,
    val g: Long,
    val h: Long,
)

internal fun fieldOrderRecord(): FieldOrderRecord {
    val random = Random(SEED)
    return FieldOrderRecord(
        random.nextLong(),
        random.nextLong(),
        random.nextLong(),
        random.nextLong(),
        random.nextLong(),
        random.nextLong(),
        random.nextLong(),
        random.nextLong(),
    )
}
