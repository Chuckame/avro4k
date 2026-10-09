@file:OptIn(
    ExperimentalAvro4kApi::class,
    InternalAvro4kApi::class,
)

package ns

import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import kotlin.Int
import kotlin.OptIn
import kotlinx.serialization.Serializable

@Serializable
@AvroGenerated("""{"type":"record","name":"Record","namespace":"ns","fields":[{"name":"field","type":"int"}]}""")
public data class Record(
    public val `field`: Int,
)
