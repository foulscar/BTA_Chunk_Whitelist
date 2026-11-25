package foulscar.chunkwhitelist.mixin;

import foulscar.chunkwhitelist.ChunkWhitelistStorage;
import foulscar.chunkwhitelist.WhitelistEmptyChunk;
import net.minecraft.core.world.chunk.Chunk;
import net.minecraft.core.world.chunk.ChunkSection;
import net.minecraft.core.world.pos.ChunkPos;
import net.minecraft.core.world.pos.ChunkPosc;
import net.minecraft.server.world.WorldServer;
import net.minecraft.server.world.chunk.provider.ChunkProviderServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Mixin(ChunkProviderServer.class)
public abstract class ChunkProviderServerMixin {
	@Shadow @Final private Chunk emptyChunk;
	@Shadow @Final private WorldServer world;
	@Shadow @Final private Map<ChunkPos, Chunk> chunkMap;
	@Shadow @Final private List<Chunk> chunkList;

	@Inject(method = "prepareChunk", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$provideEmptyUnlistedChunk(ChunkPosc chunkPos, boolean generate,
		CallbackInfoReturnable<Chunk> callback) {
		if (!ChunkWhitelistStorage.isChunkAllowed(world, chunkPos)) {
			callback.setReturnValue(chunkwhitelist$getOrCreateTransientEmptyChunk(chunkPos));
		} else {
			chunkwhitelist$removeCachedTransientEmptyChunk(chunkPos);
		}
	}

	@Inject(method = "provideChunk", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$loadUnlistedChunkAsEmpty(ChunkPosc chunkPos, boolean load,
		CallbackInfoReturnable<Chunk> callback) {
		if (!ChunkWhitelistStorage.isChunkAllowed(world, chunkPos)) {
			Chunk cachedChunk = chunkMap.get(new ChunkPos(chunkPos));
			callback.setReturnValue(load ? chunkwhitelist$getOrCreateTransientEmptyChunk(chunkPos)
				: cachedChunk == null ? emptyChunk : cachedChunk);
		} else {
			chunkwhitelist$removeCachedTransientEmptyChunk(chunkPos);
		}
	}

	@Inject(method = "chunkExists", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$checkUnlistedChunkCache(ChunkPosc chunkPos,
		CallbackInfoReturnable<Boolean> callback) {
		if (!ChunkWhitelistStorage.isChunkAllowed(world, chunkPos)) {
			callback.setReturnValue(chunkMap.containsKey(new ChunkPos(chunkPos)));
		}
	}

	@Inject(method = "populate", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$preventUnlistedPopulation(ChunkPosc chunkPos, CallbackInfo callback) {
		if (!ChunkWhitelistStorage.isChunkAllowed(world, chunkPos)) {
			callback.cancel();
		}
	}

	@Inject(method = "regenerateChunk", at = @At("HEAD"), cancellable = true)
	private void chunkwhitelist$preventUnlistedRegeneration(ChunkPosc chunkPos, CallbackInfo callback) {
		if (!ChunkWhitelistStorage.isChunkAllowed(world, chunkPos)) {
			callback.cancel();
		}
	}

	private Chunk chunkwhitelist$getOrCreateTransientEmptyChunk(ChunkPosc chunkPos) {
		ChunkPos key = new ChunkPos(chunkPos);
		Chunk cachedChunk = chunkMap.get(key);
		if (cachedChunk != null) {
			if (cachedChunk instanceof WhitelistEmptyChunk) {
				return cachedChunk;
			}
			chunkMap.remove(key);
			chunkList.remove(cachedChunk);
			cachedChunk.onUnload();
		}

		Chunk transientEmptyChunk = new WhitelistEmptyChunk(world, chunkPos);
		transientEmptyChunk.init();
		for (ChunkSection section : transientEmptyChunk.getSectionsDirect()) {
			section.init();
			section.blocks = new short[16 * 16 * 16];
			Arrays.fill(section.lightData, (byte) 0xF0);
		}
		transientEmptyChunk.neverSave = true;
		transientEmptyChunk.isTerrainPopulated = true;
		transientEmptyChunk.isLoaded = true;
		chunkMap.put(key, transientEmptyChunk);
		chunkList.add(transientEmptyChunk);
		transientEmptyChunk.onLoad();
		return transientEmptyChunk;
	}

	private void chunkwhitelist$removeCachedTransientEmptyChunk(ChunkPosc chunkPos) {
		ChunkPos key = new ChunkPos(chunkPos);
		Chunk cachedChunk = chunkMap.get(key);
		if (cachedChunk instanceof WhitelistEmptyChunk) {
			chunkMap.remove(key);
			chunkList.remove(cachedChunk);
			cachedChunk.onUnload();
		}
	}
}
