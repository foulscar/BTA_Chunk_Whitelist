package foulscar.chunkwhitelist.mixin;

import foulscar.chunkwhitelist.ChunkWhitelistStorage;
import net.minecraft.core.block.Block;
import net.minecraft.core.block.entity.TileEntity;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.TilePosc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
public abstract class WorldMixin {
	@Inject(method = "entityJoinedWorld", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$rejectEntitiesInUnlistedChunks(Entity entity, CallbackInfoReturnable<Boolean> callback) {
		World world = (World) (Object) this;
		if (!world.isClientSide && !ChunkWhitelistStorage.canEnterUnwhitelistedChunks(entity)
			&& !ChunkWhitelistStorage.isChunkAllowedAt(world, entity.x, entity.z)) {
			callback.setReturnValue(false);
		}
	}

	@Inject(method = "setBlockTypeData", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$preventUnlistedBlockChanges(TilePosc position, Block<?> block, int metadata,
		CallbackInfoReturnable<Boolean> callback) {
		chunkwhitelist$preventUnlistedBlockChange(position, callback);
	}

	@Inject(method = "setBlockTypeDataEntity", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$preventUnlistedBlockEntityChanges(TilePosc position, Block<?> block, int metadata,
		TileEntity tileEntity, CallbackInfoReturnable<Boolean> callback) {
		chunkwhitelist$preventUnlistedBlockChange(position, callback);
	}

	@Inject(method = "setBlockTypeDataRaw", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$preventUnlistedRawBlockChanges(TilePosc position, Block<?> block, int metadata,
		CallbackInfoReturnable<Boolean> callback) {
		chunkwhitelist$preventUnlistedBlockChange(position, callback);
	}

	@Inject(method = "setBlockData", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$preventUnlistedBlockDataChanges(TilePosc position, int metadata,
		CallbackInfoReturnable<Boolean> callback) {
		chunkwhitelist$preventUnlistedBlockChange(position, callback);
	}

	private void chunkwhitelist$preventUnlistedBlockChange(
		TilePosc position, CallbackInfoReturnable<Boolean> callback) {
		World world = (World) (Object) this;
		if (!world.isClientSide
			&& !ChunkWhitelistStorage.isChunkAllowed(world, position.x() >> 4, position.z() >> 4)) {
			callback.setReturnValue(false);
		}
	}
}
