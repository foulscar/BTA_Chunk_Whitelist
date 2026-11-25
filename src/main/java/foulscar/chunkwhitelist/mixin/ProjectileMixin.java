package foulscar.chunkwhitelist.mixin;

import foulscar.chunkwhitelist.ChunkWhitelistStorage;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
public abstract class ProjectileMixin {
	private static final double CHUNK_BOUNDARY_OFFSET = 0.001;

	@Inject(method = "afterTick", at = @At("HEAD"))
	private void chunkwhitelist$bounceBeforeEnteringUnlistedChunk(CallbackInfo callback) {
		Entity projectile = (Entity) (Object) this;
		if (projectile.world.isClientSide) {
			return;
		}

		double startX = projectile.x;
		double startY = projectile.y;
		double startZ = projectile.z;
		double movementX = projectile.xd;
		double movementY = projectile.yd;
		double movementZ = projectile.zd;
		int chunkX = (int) Math.floor(startX / 16.0);
		int chunkZ = (int) Math.floor(startZ / 16.0);
		double nextBoundaryX = chunkwhitelist$firstBoundaryTime(startX, movementX, chunkX);
		double nextBoundaryZ = chunkwhitelist$firstBoundaryTime(startZ, movementZ, chunkZ);
		double boundaryStepX = movementX == 0.0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(movementX);
		double boundaryStepZ = movementZ == 0.0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(movementZ);
		double collisionTime = -1.0;
		boolean crossedX = false;
		boolean crossedZ = false;

		while (Math.min(nextBoundaryX, nextBoundaryZ) <= 1.0) {
			if (nextBoundaryX < nextBoundaryZ) {
				chunkX += movementX > 0.0 ? 1 : -1;
				collisionTime = nextBoundaryX;
				crossedX = true;
				crossedZ = false;
				nextBoundaryX += boundaryStepX;
			} else if (nextBoundaryZ < nextBoundaryX) {
				chunkZ += movementZ > 0.0 ? 1 : -1;
				collisionTime = nextBoundaryZ;
				crossedX = false;
				crossedZ = true;
				nextBoundaryZ += boundaryStepZ;
			} else {
				chunkX += movementX > 0.0 ? 1 : -1;
				chunkZ += movementZ > 0.0 ? 1 : -1;
				collisionTime = nextBoundaryX;
				crossedX = true;
				crossedZ = true;
				nextBoundaryX += boundaryStepX;
				nextBoundaryZ += boundaryStepZ;
			}

			if (!ChunkWhitelistStorage.isChunkWhitelisted(projectile.world, chunkX, chunkZ)) {
				projectile.x = startX + 2.0 * movementX * collisionTime;
				projectile.y = startY + 2.0 * movementY * collisionTime;
				projectile.z = startZ + 2.0 * movementZ * collisionTime;
				if (crossedX) {
					projectile.x -= Math.copySign(CHUNK_BOUNDARY_OFFSET, movementX);
				}
				if (crossedZ) {
					projectile.z -= Math.copySign(CHUNK_BOUNDARY_OFFSET, movementZ);
				}
				projectile.xd = -projectile.xd;
				projectile.yd = -projectile.yd;
				projectile.zd = -projectile.zd;
				return;
			}
		}
	}

	private double chunkwhitelist$firstBoundaryTime(double position, double movement, int chunk) {
		if (movement > 0.0) {
			return ((chunk + 1) * 16.0 - position) / movement;
		}
		if (movement < 0.0) {
			return (chunk * 16.0 - position) / movement;
		}
		return Double.POSITIVE_INFINITY;
	}
}
