@file:OptIn(
    ExperimentalAvro4kApi::class,
    InternalAvro4kApi::class,
)

import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.AvroDoc
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import com.github.avrokotlin.avro4k.serializer.UUIDSerializer
import java.util.UUID
import kotlin.OptIn
import kotlin.collections.List
import kotlin.collections.emptyList
import kotlinx.serialization.Serializable

/**
 * Generated with the uuid logical type mapped back to java.util.UUID
 */
@Serializable
@AvroDoc("Generated with the uuid logical type mapped back to java.util.UUID")
@AvroGenerated("""{"type":"record","name":"NestedLogicalTypeUuidAsJavaUuid","doc":"Generated with the uuid logical type mapped back to java.util.UUID","fields":[{"name":"stringUuid","type":{"type":"string","logicalType":"uuid"}},{"name":"fixedUuid","type":{"type":"fixed","name":"FixedUuid","size":16,"logicalType":"uuid"}},{"name":"nullableUuid","type":["null",{"type":"string","logicalType":"uuid"}],"default":null},{"name":"uuids","type":{"type":"array","items":{"type":"string","logicalType":"uuid"}}}]}""")
public data class NestedLogicalTypeUuidAsJavaUuid(
    public val stringUuid: @Serializable(with = UUIDSerializer::class) UUID,
    public val fixedUuid: FixedUuid,
    /**
     * Default value: null
     */
    @AvroDefault("null")
    public val nullableUuid: @Serializable(with = UUIDSerializer::class) UUID?,
    public val uuids: List<@Serializable(with = UUIDSerializer::class) UUID> = emptyList(),
)
