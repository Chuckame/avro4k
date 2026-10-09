@file:OptIn(
    ExperimentalAvro4kApi::class,
    ExperimentalGlobalApi::class,
    ExperimentalUuidApi::class,
    InternalAvro4kApi::class,
)
@file:Suppress("RedundantVisibilityModifier")

import com.example.ExperimentalGlobalApi
import com.github.avrokotlin.avro4k.AvroDoc
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import com.github.avrokotlin.avro4k.serializer.KotlinUuidSerializer
import kotlin.OptIn
import kotlin.Suppress
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

/**
 * Generated with extra opt-ins and annotations on every file
 */
@Serializable
@AvroDoc("Generated with extra opt-ins and annotations on every file")
@AvroGenerated("""{"type":"record","name":"NestedRecordWithFileAnnotations","doc":"Generated with extra opt-ins and annotations on every file","fields":[{"name":"uuid","type":{"type":"string","logicalType":"uuid"}},{"name":"nested","type":{"type":"record","name":"NestedRecord","fields":[{"name":"plain","type":"string"}]}}]}""")
public data class NestedRecordWithFileAnnotations(
    public val uuid: @Serializable(with = KotlinUuidSerializer::class) Uuid,
    public val nested: NestedRecord,
)
