package foulscar.chunkwhitelist.mixin;

import foulscar.chunkwhitelist.ChunkWhitelistStorage;
import net.minecraft.core.entity.player.Player;
import net.minecraft.server.entity.player.PlayerServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "dropCurrentItem", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$preventDroppingItemsInUnlistedChunks(boolean dropStack, CallbackInfo callback) {
		Player player = (Player) (Object) this;
		if (!player.world.isClientSide
			&& !ChunkWhitelistStorage.isChunkAllowedAt(player.world, player.x, player.z)) {
			if (player instanceof PlayerServer serverPlayer) {
				serverPlayer.broadcastFullState(serverPlayer.containerMenu);
			}
			callback.cancel();
		}
	}
}
