@file:OptIn(
    ExperimentalAvro4kApi::class,
    ExperimentalUuidApi::class,
    InternalAvro4kApi::class,
)

import com.github.avrokotlin.avro4k.AvroProp
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import com.github.avrokotlin.avro4k.serializer.KotlinUuidSerializer
import kotlin.OptIn
import kotlin.jvm.JvmInline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable

@JvmInline
@Serializable
@AvroGenerated("""{"type":"string","logicalType":"uuid"}""")
public value class TestSchema(
    @AvroProp("logicalType", "uuid")
    public val `value`: @Serializable(with = KotlinUuidSerializer::class) Uuid,
)
