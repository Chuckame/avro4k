@file:OptIn(
    ExperimentalAvro4kApi::class,
    InternalAvro4kApi::class,
)

package items.ns

import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import kotlin.ByteArray
import kotlin.OptIn
import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
@SerialName("items.ns.Item")
@AvroGenerated("""{"type":"fixed","name":"Item","namespace":"items.ns","size":3}""")
public value class Item(
    @AvroFixed(size = 3)
    public val `value`: ByteArray,
)
