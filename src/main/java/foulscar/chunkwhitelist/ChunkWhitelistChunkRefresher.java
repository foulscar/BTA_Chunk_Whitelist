package foulscar.chunkwhitelist;

import foulscar.chunkwhitelist.mixin.ChunkProviderServerAccessor;
import foulscar.chunkwhitelist.mixin.PlayerInstanceAccessor;
import foulscar.chunkwhitelist.mixin.PlayerManagerAccessor;
import net.minecraft.core.world.chunk.Chunk;
import net.minecraft.core.world.chunk.ChunkCoordinate;
import net.minecraft.core.world.pos.ChunkPos;
import net.minecraft.server.entity.player.PlayerServer;
import net.minecraft.server.net.PlayerHash;
import net.minecraft.server.player.PlayerManager;
import net.minecraft.server.world.WorldServer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ChunkWhitelistChunkRefresher {
	private ChunkWhitelistChunkRefresher() {
	}

	static void refreshLoadedChunksForWhitelistState(WorldServer world, boolean enabled) {
		ChunkProviderServerAccessor provider = (ChunkProviderServerAccessor) world.getChunkProvider();
		List<ChunkPos> chunksToRefresh = new ArrayList<>();
		for (Chunk chunk : List.copyOf(provider.chunkwhitelist$getLoadedChunks())) {
			boolean fakeChunk = chunk instanceof WhitelistEmptyChunk;
			boolean shouldRefresh = enabled
				? !fakeChunk && !ChunkWhitelistStorage.isChunkWhitelisted(world, chunk.pos)
				: fakeChunk;
			if (shouldRefresh) {
				if (enabled && !fakeChunk) {
					provider.chunkwhitelist$saveChunkToFile(chunk);
					world.getChunkProvider().prepareChunk(new ChunkPos(chunk.pos), true);
				}
				chunksToRefresh.add(new ChunkPos(chunk.pos));
			}
		}
		queueChunkRefresh(world, chunksToRefresh);
	}

	static void refreshChangedChunks(WorldServer world, List<ChunkPos> changedChunks) {
		ChunkProviderServerAccessor provider = (ChunkProviderServerAccessor) world.getChunkProvider();
		Set<Long> changedPositions = new HashSet<>();
		for (ChunkPos chunk : changedChunks) {
			changedPositions.add(getChunkWatcherKey(chunk.x, chunk.z));
		}

		for (Chunk loadedChunk : List.copyOf(provider.chunkwhitelist$getLoadedChunks())) {
			if (!changedPositions.contains(getChunkWatcherKey(loadedChunk.pos.x, loadedChunk.pos.z))) {
				continue;
			}
			if (!ChunkWhitelistStorage.isChunkAllowed(world, loadedChunk.pos)) {
				if (!(loadedChunk instanceof WhitelistEmptyChunk)) {
					provider.chunkwhitelist$saveChunkToFile(loadedChunk);
				}
				world.getChunkProvider().prepareChunk(new ChunkPos(loadedChunk.pos), true);
			}
		}
		queueChunkRefresh(world, changedChunks);
	}

	private static void queueChunkRefresh(WorldServer world, List<ChunkPos> chunksToRefresh) {
		PlayerManager playerManager = world.mcServer.playerList.getPlayerManager(world.dimension.id);
		if (playerManager == null || chunksToRefresh.isEmpty()) {
			return;
		}

		PlayerManagerAccessor managerAccessor = (PlayerManagerAccessor) playerManager;
		Map<PlayerServer, List<ChunkCoordinate>> playerChunks = new HashMap<>();
		Map<PlayerServer, Set<Long>> playerChunkKeys = new HashMap<>();
		for (ChunkPos chunk : chunksToRefresh) {
			Object chunkWatcher = managerAccessor.chunkWhitelist$getPlayerInstances()
				.getValueByKey(getChunkWatcherKey(chunk.x, chunk.z));
			if (chunkWatcher == null) {
				continue;
			}

			for (PlayerServer player : ((PlayerInstanceAccessor) chunkWatcher).chunkWhitelist$getPlayers()) {
				List<ChunkCoordinate> chunks = playerChunks.computeIfAbsent(player, ignored -> new ArrayList<>());
				Set<Long> chunkKeys = playerChunkKeys.computeIfAbsent(player, ignored -> new HashSet<>());
				if (chunkKeys.add(getChunkWatcherKey(chunk.x, chunk.z))) {
					chunks.add(new ChunkCoordinate(chunk.x, chunk.z));
				}
			}
		}

		for (Map.Entry<PlayerServer, List<ChunkCoordinate>> entry : playerChunks.entrySet()) {
			PlayerServer player = entry.getKey();
			List<ChunkCoordinate> chunks = entry.getValue();
			int playerChunkX = (int) player.x >> 4;
			int playerChunkZ = (int) player.z >> 4;
			chunks.sort(Comparator
				.comparingLong((ChunkCoordinate chunk) ->
					Math.abs((long) chunk.x - playerChunkX) + Math.abs((long) chunk.z - playerChunkZ))
				.thenComparingInt(chunk -> chunk.x)
				.thenComparingInt(chunk -> chunk.z));

			Set<Long> chunkKeys = playerChunkKeys.get(player);
			player.loadedChunks.removeIf(queued -> chunkKeys.contains(getChunkWatcherKey(queued.x, queued.z)));
			player.loadedChunks.addAll(0, chunks);
		}
	}

	static long getChunkWatcherKey(int chunkX, int chunkZ) {
		return (((long) chunkZ + Integer.MAX_VALUE) << 32) | ((long) chunkX + Integer.MAX_VALUE);
	}
}
