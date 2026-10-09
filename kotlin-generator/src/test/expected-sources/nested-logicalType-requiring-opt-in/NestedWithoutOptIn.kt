@file:OptIn(
    ExperimentalAvro4kApi::class,
    InternalAvro4kApi::class,
)

import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import kotlin.OptIn
import kotlin.String
import kotlinx.serialization.Serializable

@Serializable
@AvroGenerated("""{"type":"record","name":"NestedWithoutOptIn","fields":[{"name":"plain","type":"string"}]}""")
public data class NestedWithoutOptIn(
    public val plain: String,
)
