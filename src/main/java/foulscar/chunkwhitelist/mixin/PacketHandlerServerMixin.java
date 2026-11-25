package foulscar.chunkwhitelist.mixin;

import foulscar.chunkwhitelist.ChunkWhitelistStorage;
import net.minecraft.core.net.packet.PacketBlockUpdate;
import net.minecraft.core.net.packet.PacketMovePlayer;
import net.minecraft.core.net.packet.PacketPlayerAction;
import net.minecraft.core.net.packet.PacketSetEntityMotion;
import net.minecraft.core.net.packet.PacketUseOrPlaceItemStack;
import net.minecraft.server.entity.player.PlayerServer;
import net.minecraft.server.net.handler.PacketHandlerServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PacketHandlerServer.class)
public abstract class PacketHandlerServerMixin {
	@Shadow private PlayerServer playerEntity;

	@Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$rejectMovementIntoUnlistedChunks(PacketMovePlayer packet, CallbackInfo callback) {
		if (packet.hasPosition && !ChunkWhitelistStorage.canEnterUnwhitelistedChunks(playerEntity)
			&& !ChunkWhitelistStorage.isChunkAllowedAt(
			playerEntity.world, packet.x, packet.z)) {
			chunkwhitelist$bouncePlayer(packet.x, packet.z);
			callback.cancel();
		}
	}

	@Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$rejectActionsInUnlistedChunks(PacketPlayerAction packet, CallbackInfo callback) {
		boolean droppingItem = packet.action == PacketPlayerAction.ACTION_DROP_ITEM_SINGLE
			|| packet.action == PacketPlayerAction.ACTION_DROP_ITEM_STACK;
		boolean whitelisted = droppingItem
			? ChunkWhitelistStorage.isChunkAllowedAt(playerEntity.world, playerEntity.x, playerEntity.z)
			: ChunkWhitelistStorage.isChunkAllowed(
				playerEntity.world, packet.xPosition >> 4, packet.zPosition >> 4);
		if (!whitelisted) {
			if (!droppingItem) {
				chunkwhitelist$sendBlockUpdate(packet.xPosition, packet.yPosition, packet.zPosition);
			}
			chunkwhitelist$syncPlayerInventory();
			callback.cancel();
		}
	}

	@Inject(method = "handleUseOrPlaceItem", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$rejectItemUseInUnlistedChunks(
		PacketUseOrPlaceItemStack packet, CallbackInfo callback) {
		boolean rejected = switch (packet.type) {
			case PacketUseOrPlaceItemStack.TYPE_USE_OR_PLACE_ON_TILE, PacketUseOrPlaceItemStack.TYPE_PLACE_ONLY ->
				!chunkwhitelist$isBlockTargetWhitelisted(packet);
			case PacketUseOrPlaceItemStack.TYPE_USE_ON_NOTHING ->
				!ChunkWhitelistStorage.isChunkAllowedAt(playerEntity.world, playerEntity.x, playerEntity.z);
			default -> true;
		};
		if (rejected) {
			if (packet.type == PacketUseOrPlaceItemStack.TYPE_USE_OR_PLACE_ON_TILE
				|| packet.type == PacketUseOrPlaceItemStack.TYPE_PLACE_ONLY) {
				chunkwhitelist$sendBlockUpdate(packet.xPosition, packet.yPosition, packet.zPosition);
			}
			if (packet.direction != null
				&& (packet.type == PacketUseOrPlaceItemStack.TYPE_USE_OR_PLACE_ON_TILE
					|| packet.type == PacketUseOrPlaceItemStack.TYPE_PLACE_ONLY)) {
				chunkwhitelist$sendBlockUpdate(
					packet.xPosition + packet.direction.offsetX(),
					packet.yPosition + packet.direction.offsetY(),
					packet.zPosition + packet.direction.offsetZ());
			}
			chunkwhitelist$syncPlayerInventory();
			callback.cancel();
		}
	}

	private boolean chunkwhitelist$isBlockTargetWhitelisted(PacketUseOrPlaceItemStack packet) {
		boolean targetChunkWhitelisted = ChunkWhitelistStorage.isChunkAllowed(
			playerEntity.world, packet.xPosition >> 4, packet.zPosition >> 4);
		boolean placementChunkWhitelisted = packet.direction == null
			|| ChunkWhitelistStorage.isChunkAllowed(playerEntity.world,
				(packet.xPosition + packet.direction.offsetX()) >> 4,
				(packet.zPosition + packet.direction.offsetZ()) >> 4);
		return targetChunkWhitelisted && placementChunkWhitelisted;
	}

	private void chunkwhitelist$bouncePlayer(double targetX, double targetZ) {
		double xMovement = targetX - playerEntity.x;
		double zMovement = targetZ - playerEntity.z;
		int currentChunkX = (int) Math.floor(playerEntity.x / 16.0);
		int currentChunkZ = (int) Math.floor(playerEntity.z / 16.0);
		int targetChunkX = (int) Math.floor(targetX / 16.0);
		int targetChunkZ = (int) Math.floor(targetZ / 16.0);
		boolean bounceX = targetChunkX != currentChunkX
			&& !ChunkWhitelistStorage.isChunkAllowed(playerEntity.world, targetChunkX, currentChunkZ);
		boolean bounceZ = targetChunkZ != currentChunkZ
			&& !ChunkWhitelistStorage.isChunkAllowed(playerEntity.world, currentChunkX, targetChunkZ);
		if (!bounceX && !bounceZ) {
			bounceX = xMovement != 0.0;
			bounceZ = zMovement != 0.0;
		}

		if (bounceX) {
			playerEntity.xd = -chunkwhitelist$getBounceSpeed(xMovement);
		}
		if (bounceZ) {
			playerEntity.zd = -chunkwhitelist$getBounceSpeed(zMovement);
		}

		PacketHandlerServer handler = (PacketHandlerServer) (Object) this;
		handler.teleport(playerEntity.x, playerEntity.y, playerEntity.z);
		handler.sendPacket(new PacketSetEntityMotion(playerEntity));
	}

	private double chunkwhitelist$getBounceSpeed(double movement) {
		if (movement == 0.0) {
			return 0.0;
		}
		return Math.copySign(Math.max(Math.min(Math.abs(movement), 1.2), 1.1), movement);
	}

	private void chunkwhitelist$sendBlockUpdate(int x, int y, int z) {
		((PacketHandlerServer) (Object) this).sendPacket(
			new PacketBlockUpdate(x, y, z, playerEntity.world));
	}

	private void chunkwhitelist$syncPlayerInventory() {
		playerEntity.broadcastFullState(playerEntity.containerMenu);
	}
}
