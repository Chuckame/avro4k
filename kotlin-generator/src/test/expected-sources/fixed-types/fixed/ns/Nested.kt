@file:OptIn(
    ExperimentalAvro4kApi::class,
    InternalAvro4kApi::class,
)

package fixed.ns

import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.OptIn
import kotlinx.serialization.Serializable

@Serializable
@AvroGenerated("""{"type":"record","name":"Nested","namespace":"fixed.ns","fields":[{"name":"plain","type":{"type":"fixed","name":"Plain","size":4}}]}""")
public data class Nested(
    public val plain: Plain,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Nested
        if (!plain.value.contentEquals(other.plain.value)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = plain.value.contentHashCode()
        return result
    }
}
