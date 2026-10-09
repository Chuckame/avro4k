@file:OptIn(
    ExperimentalAvro4kApi::class,
    ExperimentalCustomApi::class,
    ExperimentalUuidApi::class,
    InternalAvro4kApi::class,
)

import com.example.ExperimentalCustomApi
import com.example.OptInRequiringType
import com.example.OptInRequiringTypeSerializer
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroDoc
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import com.github.avrokotlin.avro4k.serializer.KotlinUuidSerializer
import kotlin.Int
import kotlin.OptIn
import kotlin.collections.List
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * Generated with a logical type whose Kotlin class requires an opt-in
 */
@Serializable
@AvroDoc("Generated with a logical type whose Kotlin class requires an opt-in")
@AvroGenerated("""{"type":"record","name":"NestedLogicalTypeRequiringOptIn","doc":"Generated with a logical type whose Kotlin class requires an opt-in","fields":[{"name":"custom","type":{"type":"string","logicalType":"optInRequiringLogicalType"}},{"name":"nullableCustoms","type":["null",{"type":"array","items":{"type":"string","logicalType":"optInRequiringLogicalType"}}],"default":null},{"name":"union","type":["int",{"type":"string","logicalType":"optInRequiringLogicalType"}]},{"name":"uuid","type":{"type":"string","logicalType":"uuid"}},{"name":"nested","type":{"type":"record","name":"NestedWithoutOptIn","fields":[{"name":"plain","type":"string"}]}},{"name":"nestedUsingTheType","type":{"type":"record","name":"NestedUsingTheType","fields":[{"name":"customs","type":{"type":"map","values":{"type":"string","logicalType":"optInRequiringLogicalType"}}}]}}]}""")
public data class NestedLogicalTypeRequiringOptIn(
    public val custom: @Serializable(with = OptInRequiringTypeSerializer::class) OptInRequiringType,
    /**
     * Default value: null
     */
    @AvroDefault("null")
    public val nullableCustoms:
            List<@Serializable(with = OptInRequiringTypeSerializer::class) OptInRequiringType>?,
    public val union: UnionUnion,
    public val uuid: @Serializable(with = KotlinUuidSerializer::class) Uuid,
    public val nested: NestedWithoutOptIn,
    public val nestedUsingTheType: NestedUsingTheType,
) {
    @Serializable
    @AvroGenerated("""["int",{"type":"string","logicalType":"optInRequiringLogicalType"}]""")
    public sealed interface UnionUnion {
        @JvmInline
        @Serializable
        public value class ForInt(
            public val `value`: Int,
        ) : UnionUnion

        @JvmInline
        @Serializable
        public value class ForString(
            public val `value`:
                    @Serializable(with = OptInRequiringTypeSerializer::class) OptInRequiringType,
        ) : UnionUnion
    }
}
