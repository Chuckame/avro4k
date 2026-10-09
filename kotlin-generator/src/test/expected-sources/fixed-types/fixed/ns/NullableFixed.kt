@file:OptIn(
    ExperimentalAvro4kApi::class,
    InternalAvro4kApi::class,
)

package fixed.ns

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
@SerialName("fixed.ns.NullableFixed")
@AvroGenerated("""{"type":"fixed","name":"NullableFixed","namespace":"fixed.ns","size":2}""")
public value class NullableFixed(
    @AvroFixed(size = 2)
    public val `value`: ByteArray,
)
