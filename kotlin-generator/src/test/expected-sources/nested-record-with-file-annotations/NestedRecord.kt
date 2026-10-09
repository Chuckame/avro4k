@file:OptIn(
    ExperimentalAvro4kApi::class,
    ExperimentalGlobalApi::class,
    InternalAvro4kApi::class,
)
@file:Suppress("RedundantVisibilityModifier")

import com.example.ExperimentalGlobalApi
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import kotlin.OptIn
import kotlin.String
import kotlin.Suppress
import kotlinx.serialization.Serializable

@Serializable
@AvroGenerated("""{"type":"record","name":"NestedRecord","fields":[{"name":"plain","type":"string"}]}""")
public data class NestedRecord(
    public val plain: String,
)
