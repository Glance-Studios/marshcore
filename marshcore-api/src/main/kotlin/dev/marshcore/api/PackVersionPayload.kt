package dev.marshcore.api

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * C2S: the client reports its baked-in MarshLands pack version. SAME id ("marshlands:pack_version")
 * and encoding as marshcore-client's copy, so they interoperate across the two mods.
 */
class PackVersionPayload(@JvmField val version: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<PackVersionPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("marshlands", "pack_version"))

        @JvmField
        val CODEC: StreamCodec<ByteBuf, PackVersionPayload> =
            ByteBufCodecs.STRING_UTF8.map({ PackVersionPayload(it) }, { it.version })
    }
}
