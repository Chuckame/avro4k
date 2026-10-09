@file:OptIn(
    ExperimentalAvro4kApi::class,
    InternalAvro4kApi::class,
)

package ns

import com.github.avrokotlin.avro4k.AvroDoc
import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import kotlin.ByteArray
import kotlin.OptIn
import kotlin.jvm.JvmInline
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * the doc
 */
@JvmInline
@Serializable
@SerialName("ns.Fixed")
@AvroDoc("the doc")
@AvroGenerated("""{"type":"fixed","name":"Fixed","namespace":"ns","doc":"the doc","size":3}""")
public value class Fixed(
    @AvroFixed(size = 3)
    public val `value`: ByteArray,
)
