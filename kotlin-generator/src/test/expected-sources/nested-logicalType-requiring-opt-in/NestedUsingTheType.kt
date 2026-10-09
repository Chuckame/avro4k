@file:OptIn(
    ExperimentalAvro4kApi::class,
    ExperimentalCustomApi::class,
    InternalAvro4kApi::class,
)

import com.example.ExperimentalCustomApi
import com.example.OptInRequiringType
import com.example.OptInRequiringTypeSerializer
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import kotlin.OptIn
import kotlin.String
import kotlin.collections.Map
import kotlin.collections.emptyMap
import kotlinx.serialization.Serializable

@Serializable
@AvroGenerated("""{"type":"record","name":"NestedUsingTheType","fields":[{"name":"customs","type":{"type":"map","values":{"type":"string","logicalType":"optInRequiringLogicalType"}}}]}""")
public data class NestedUsingTheType(
    public val customs:
            Map<String, @Serializable(with = OptInRequiringTypeSerializer::class) OptInRequiringType> = emptyMap(),
)
