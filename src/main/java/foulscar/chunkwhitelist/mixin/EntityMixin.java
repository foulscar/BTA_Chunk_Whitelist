package foulscar.chunkwhitelist.mixin;

import foulscar.chunkwhitelist.ChunkWhitelistStorage;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.MobFlying;
import net.minecraft.core.entity.MobPathfinder;
import net.minecraft.core.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityMixin {
	@Shadow public World world;
	@Shadow public double x;
	@Shadow public double z;

	@Inject(method = "move", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$preventMovementIntoUnlistedChunks(
		double xMovement, double yMovement, double zMovement, CallbackInfo callback) {
		if (world != null && !world.isClientSide
			&& !ChunkWhitelistStorage.canEnterUnwhitelistedChunks((Entity) (Object) this)
			&& !ChunkWhitelistStorage.isChunkAllowedAt(world, x + xMovement, z + zMovement)) {
			Entity entity = (Entity) (Object) this;
			chunkwhitelist$bounceFromUnlistedChunk(xMovement, zMovement);
			chunkwhitelist$resetNavigation(entity);
			callback.cancel();
		}
	}

	@Inject(method = "setPos", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$preventPositionInUnlistedChunks(double x, double y, double z, CallbackInfo callback) {
		if (world != null && !world.isClientSide
			&& !ChunkWhitelistStorage.canEnterUnwhitelistedChunks((Entity) (Object) this)
			&& !ChunkWhitelistStorage.isChunkAllowedAt(world, x, z)) {
			callback.cancel();
		}
	}

	@Inject(method = "absMoveTo", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$preventAbsoluteMovementIntoUnlistedChunks(
		double x, double y, double z, float yaw, float pitch, CallbackInfo callback) {
		if (world != null && !world.isClientSide
			&& !ChunkWhitelistStorage.canEnterUnwhitelistedChunks((Entity) (Object) this)
			&& !ChunkWhitelistStorage.isChunkAllowedAt(world, x, z)) {
			callback.cancel();
		}
	}

	private void chunkwhitelist$bounceFromUnlistedChunk(double xMovement, double zMovement) {
		Entity entity = (Entity) (Object) this;
		if (entity instanceof MobFlying) {
			entity.xd = 0.0;
			entity.zd = 0.0;
			chunkwhitelist$resetFlyingWaypoint(entity);
			return;
		}

		boolean blockXMovement = !ChunkWhitelistStorage.isChunkAllowedAt(world, x + xMovement, z);
		boolean blockZMovement = !ChunkWhitelistStorage.isChunkAllowedAt(world, x, z + zMovement);
		if (!blockXMovement && !blockZMovement) {
			blockXMovement = xMovement != 0.0;
			blockZMovement = zMovement != 0.0;
		}
		if (blockXMovement) {
			entity.xd = -chunkwhitelist$getBounceSpeed(xMovement);
		}
		if (blockZMovement) {
			entity.zd = -chunkwhitelist$getBounceSpeed(zMovement);
		}
	}

	private void chunkwhitelist$resetNavigation(Entity entity) {
		if (entity instanceof MobPathfinder) {
			((MobPathfinderAccessor) entity).chunkwhitelist$setPathToEntity(null);
		}
	}

	private void chunkwhitelist$resetFlyingWaypoint(Entity entity) {
		int chunkX = (int) Math.floor(entity.x / 16.0);
		int chunkZ = (int) Math.floor(entity.z / 16.0);
		chunkwhitelist$setPublicDoubleField(entity, "waypointX", chunkX * 16.0 + 8.0);
		chunkwhitelist$setPublicDoubleField(entity, "waypointY", entity.y);
		chunkwhitelist$setPublicDoubleField(entity, "waypointZ", chunkZ * 16.0 + 8.0);
		chunkwhitelist$setPublicIntField(entity, "courseChangeCooldown", 20);
	}

	private void chunkwhitelist$setPublicDoubleField(Entity entity, String fieldName, double value) {
		try {
			entity.getClass().getField(fieldName).setDouble(entity, value);
		} catch (NoSuchFieldException ignored) {
		} catch (IllegalAccessException exception) {
			throw new IllegalStateException("Unable to reset entity navigation field " + fieldName, exception);
		}
	}

	private void chunkwhitelist$setPublicIntField(Entity entity, String fieldName, int value) {
		try {
			entity.getClass().getField(fieldName).setInt(entity, value);
		} catch (NoSuchFieldException ignored) {
		} catch (IllegalAccessException exception) {
			throw new IllegalStateException("Unable to reset entity navigation field " + fieldName, exception);
		}
	}

	private double chunkwhitelist$getBounceSpeed(double movement) {
		if (movement == 0.0) {
			return 0.0;
		}
		return Math.copySign(Math.max(Math.abs(movement), 1.1), movement);
	}
}
