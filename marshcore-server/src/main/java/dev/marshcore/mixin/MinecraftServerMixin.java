package dev.marshcore.mixin;

import dev.marshcore.MarshCoreConfig;
import dev.marshcore.MarshCoreMod;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * While maintenance mode is on, rewrite the status-ping's version field so the server-list "version"
 * slot shows the maintenance text (in red) and the client is treated as protocol-incompatible.
 */
@Mixin(MinecraftServer.class)
public class MinecraftServerMixin {

    @Inject(method = "getStatus", at = @At("RETURN"), cancellable = true)
    private void marshcore$maintenanceStatus(CallbackInfoReturnable<ServerStatus> cir) {
        MarshCoreConfig cfg = MarshCoreMod.INSTANCE.getConfig();
        if (!cfg.maintenanceEnabled) return;
        ServerStatus s = cir.getReturnValue();
        if (s == null) return;
        // protocol -1 never matches a real client, so it renders red + "can't connect".
        ServerStatus.Version version = new ServerStatus.Version(cfg.maintenanceVersionText, -1);
        cir.setReturnValue(new ServerStatus(
            s.description(),
            s.players(),
            Optional.of(version),
            s.favicon(),
            s.enforcesSecureChat()
        ));
    }
}
