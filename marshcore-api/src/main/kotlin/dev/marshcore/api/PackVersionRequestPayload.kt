package dev.marshcore.api

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

/**
 * S2C configuration-phase query for the client's pack version, sent by [PackGate], which holds the
 * phase until marshcore-client answers. It carries the verdict ([required], [url], [mandatory]) up
 * front, so the client can put its own button on the disconnect screen. The id and encoding match
 * marshcore-client's copy, so changing the field list means bumping both.
 */
class PackVersionRequestPayload(
    @JvmField val required: String,
    @JvmField val url: String,
    @JvmField val mandatory: Boolean,
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

    companion object {
        @JvmField
        val TYPE: CustomPacketPayload.Type<PackVersionRequestPayload> =
            CustomPacketPayload.Type(Identifier.fromNamespaceAndPath("marshlands", "pack_version_request"))

        @JvmField
        val CODEC: StreamCodec<ByteBuf, PackVersionRequestPayload> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, { it.required },
            ByteBufCodecs.STRING_UTF8, { it.url },
            ByteBufCodecs.BOOL, { it.mandatory },
            { required, url, mandatory -> PackVersionRequestPayload(required, url, mandatory) },
        )
    }
}
