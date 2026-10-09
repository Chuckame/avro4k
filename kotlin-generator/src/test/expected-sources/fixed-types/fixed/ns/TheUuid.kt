@file:OptIn(
    ExperimentalAvro4kApi::class,
    ExperimentalUuidApi::class,
    InternalAvro4kApi::class,
)

package fixed.ns

import com.github.avrokotlin.avro4k.AvroFixed
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import com.github.avrokotlin.avro4k.serializer.KotlinUuidSerializer
import kotlin.OptIn
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
@SerialName("fixed.ns.TheUuid")
@AvroGenerated("""{"type":"fixed","name":"TheUuid","namespace":"fixed.ns","size":16,"logicalType":"uuid"}""")
public value class TheUuid(
    @AvroFixed(size = 16)
    public val `value`: @Serializable(with = KotlinUuidSerializer::class) Uuid,
)
