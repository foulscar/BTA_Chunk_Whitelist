package foulscar.chunkwhitelist.mixin;

import foulscar.chunkwhitelist.ChunkWhitelistStorage;
import net.minecraft.core.world.chunk.ChunkCoordinate;
import net.minecraft.core.world.pos.ChunkPos;
import net.minecraft.server.entity.player.PlayerServer;
import net.minecraft.server.world.WorldServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerServer.class)
public abstract class PlayerServerMixin {
	@Inject(method = "isChunkNeighborhoodLoaded", at = @At("RETURN"), cancellable = true)
	private void chunkwhitelist$allowChunkDataSend(WorldServer world, ChunkCoordinate pos,
		CallbackInfoReturnable<Boolean> callback) {
		if (callback.getReturnValue()) {
			return;
		}
		if (!ChunkWhitelistStorage.isWhitelistEnabled(world)) {
			return;
		}

		ChunkPos neighbor = new ChunkPos(pos.x, pos.z);
		boolean hasUnloadedUnwhitelistedNeighbor = false;
		for (int offsetX = -1; offsetX <= 1; offsetX++) {
			for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
				neighbor.set(pos.x + offsetX, pos.z + offsetZ);
				if (!world.getChunkProvider().isChunkLoaded(neighbor)) {
					if (ChunkWhitelistStorage.isChunkWhitelisted(world, neighbor.x, neighbor.z)) {
						return;
					}
					hasUnloadedUnwhitelistedNeighbor = true;
				}
			}
		}

		if (hasUnloadedUnwhitelistedNeighbor) {
			callback.setReturnValue(true);
		}
	}
}
