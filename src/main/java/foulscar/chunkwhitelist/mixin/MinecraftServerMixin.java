package foulscar.chunkwhitelist.mixin;

import foulscar.chunkwhitelist.ChunkWhitelistStorage;
import net.minecraft.core.world.save.ISaveFormat;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Inject(
		method = "initWorld",
		at = @At(value = "INVOKE", target = "Ljava/lang/System;currentTimeMillis()J", ordinal = 0)
	)
	private void chunkwhitelist$initializeDimensionWhitelists(ISaveFormat saveFormat, String worldName,
		long seed, CallbackInfo callback) {
		ChunkWhitelistStorage.loadAllDimensionWhitelists((MinecraftServer) (Object) this);
	}
}
