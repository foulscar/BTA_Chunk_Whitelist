package foulscar.chunkwhitelist.mixin;

import foulscar.chunkwhitelist.ChunkWhitelistStorage;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.world.pathfinder.Node;
import net.minecraft.core.world.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PathFinder.class)
public abstract class PathFinderMixin {
	@Inject(
		method = "getNode(Lnet/minecraft/core/entity/Entity;IIILnet/minecraft/core/world/pathfinder/Node;I)Lnet/minecraft/core/world/pathfinder/Node;",
		at = @At("HEAD"),
		cancellable = true
	)
	private void chunkwhitelist$rejectNodesInUnlistedChunks(
		Entity entity, int x, int y, int z, Node parent, int step, CallbackInfoReturnable<Node> callback) {
		if (!entity.world.isClientSide
			&& !ChunkWhitelistStorage.isChunkAllowed(entity.world, x >> 4, z >> 4)) {
			callback.setReturnValue(null);
		}
	}
}
