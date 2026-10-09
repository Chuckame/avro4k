@file:OptIn(
    ExperimentalAvro4kApi::class,
    InternalAvro4kApi::class,
)

package fixed.ns

import NoNamespace
import com.github.avrokotlin.avro4k.AvroDefault
import com.github.avrokotlin.avro4k.ExperimentalAvro4kApi
import com.github.avrokotlin.avro4k.InternalAvro4kApi
import com.github.avrokotlin.avro4k.`internal`.AvroGenerated
import items.ns.Item
import kotlin.Any
import kotlin.Boolean
import kotlin.Int
import kotlin.OptIn
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable
import other.ns.Documented

@Serializable
@AvroGenerated("""{"type":"record","name":"FixedTypes","namespace":"fixed.ns","fields":[{"name":"plain","type":{"type":"fixed","name":"Plain","size":4}},{"name":"documented","type":{"type":"fixed","name":"Documented","namespace":"other.ns","doc":"a fixed with a doc and aliases","size":8,"aliases":["OldDocumented","legacy.ns.LegacyDocumented"]}},{"name":"plainAgain","type":"Plain"},{"name":"nullable","type":["null",{"type":"fixed","name":"NullableFixed","size":2}],"default":null},{"name":"array","type":{"type":"array","items":{"type":"fixed","name":"Item","namespace":"items.ns","size":3}}},{"name":"map","type":{"type":"map","values":"items.ns.Item"}},{"name":"noNamespace","type":{"type":"fixed","name":"NoNamespace","namespace":"","size":1}},{"name":"uuid","type":{"type":"fixed","name":"TheUuid","size":16,"logicalType":"uuid"}},{"name":"decimal","type":{"type":"fixed","name":"TheDecimal","size":8,"logicalType":"decimal","precision":10,"scale":2}},{"name":"withDefault","type":{"type":"fixed","name":"WithDefault","size":2},"default":"\u0001\u0002"},{"name":"union","type":["int","Plain",{"type":"fixed","name":"UnionOnly","size":5}]},{"name":"nested","type":{"type":"record","name":"Nested","fields":[{"name":"plain","type":"Plain"}]}}]}""")
public data class FixedTypes(
    public val plain: Plain,
    public val documented: Documented,
    public val plainAgain: Plain,
    /**
     * Default value: null
     */
    @AvroDefault("null")
    public val nullable: NullableFixed? = null,
    public val array: List<Item> = emptyList(),
    public val map: Map<String, Item> = emptyMap(),
    public val noNamespace: NoNamespace,
    public val uuid: TheUuid,
    public val decimal: TheDecimal,
    /**
     * Default value: [1, 2]
     */
    @AvroDefault("\u0001\u0002")
    public val withDefault: WithDefault = WithDefault(byteArrayOf(1, 2)),
    public val union: UnionUnion,
    public val nested: Nested,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FixedTypes
        if (!plain.value.contentEquals(other.plain.value)) return false
        if (!documented.value.contentEquals(other.documented.value)) return false
        if (!plainAgain.value.contentEquals(other.plainAgain.value)) return false
        if (!nullable?.value.contentEquals(other.nullable?.value)) return false
        if (array != other.array) return false
        if (map != other.map) return false
        if (!noNamespace.value.contentEquals(other.noNamespace.value)) return false
        if (uuid != other.uuid) return false
        if (decimal != other.decimal) return false
        if (!withDefault.value.contentEquals(other.withDefault.value)) return false
        if (union != other.union) return false
        if (nested != other.nested) return false
        return true
    }

    override fun hashCode(): Int {
        var result = plain.value.contentHashCode()
        result = 31 * result + documented.value.contentHashCode()
        result = 31 * result + plainAgain.value.contentHashCode()
        result = 31 * result + nullable?.value.contentHashCode()
        result = 31 * result + array.hashCode()
        result = 31 * result + map.hashCode()
        result = 31 * result + noNamespace.value.contentHashCode()
        result = 31 * result + uuid.hashCode()
        result = 31 * result + decimal.hashCode()
        result = 31 * result + withDefault.value.contentHashCode()
        result = 31 * result + union.hashCode()
        result = 31 * result + nested.hashCode()
        return result
    }

    @Serializable
    @AvroGenerated("""["int",{"type":"fixed","name":"Plain","namespace":"fixed.ns","size":4},{"type":"fixed","name":"UnionOnly","namespace":"fixed.ns","size":5}]""")
    public sealed interface UnionUnion {
        @JvmInline
        @Serializable
        public value class ForInt(
            public val `value`: Int,
        ) : UnionUnion

        @JvmInline
        @Serializable
        public value class ForPlain(
            public val `value`: Plain,
        ) : UnionUnion

        @JvmInline
        @Serializable
        public value class ForUnionOnly(
            public val `value`: UnionOnly,
        ) : UnionUnion
    }
}
