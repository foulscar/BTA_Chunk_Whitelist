package foulscar.chunkwhitelist;

import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.player.gamemode.Gamemodes;
import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.ChunkPos;
import net.minecraft.core.world.pos.ChunkPosc;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.WorldServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ChunkWhitelistStorage {
	private static final Logger LOGGER = ChunkWhitelist.LOGGER;
	static final int MAX_CHUNK_UPDATES_PER_COMMAND = 4096;
	private static final ConcurrentMap<Path, Set<Long>> WHITELISTED_CHUNKS_BY_FILE = new ConcurrentHashMap<>();
	private static final ConcurrentMap<Path, Map<String, Boolean>> DIMENSION_SETTINGS_BY_FILE =
		new ConcurrentHashMap<>();

	private ChunkWhitelistStorage() {
	}

	public static boolean isChunkWhitelisted(World world, int chunkX, int chunkZ) {
		return getWhitelistedChunks(world).contains(packChunkPosition(chunkX, chunkZ));
	}

	public static boolean isWhitelistEnabled(World world) {
		return getDimensionSettings(world).getOrDefault(dimensionKey(world), true);
	}

	public static boolean isChunkAllowed(World world, int chunkX, int chunkZ) {
		return !isWhitelistEnabled(world) || isChunkWhitelisted(world, chunkX, chunkZ);
	}

	public static boolean isChunkAllowed(World world, ChunkPosc chunkPos) {
		return isChunkAllowed(world, chunkPos.x(), chunkPos.z());
	}

	public static boolean isChunkAllowedAt(World world, double x, double z) {
		return isChunkAllowed(world, (int) Math.floor(x / 16.0), (int) Math.floor(z / 16.0));
	}

	public static synchronized boolean setWhitelistEnabled(World world, boolean enabled) throws IOException {
		Path settingsFile = dimensionSettingsFileFor(world);
		Map<String, Boolean> settings = getDimensionSettings(world);
		if (isWhitelistEnabled(world) == enabled) {
			return false;
		}

		Map<String, Boolean> updatedSettings = new HashMap<>(settings);
		updatedSettings.put(dimensionKey(world), enabled);
		ChunkWhitelistIO.writeDimensionSettings(settingsFile, updatedSettings);
		DIMENSION_SETTINGS_BY_FILE.put(settingsFile, Map.copyOf(updatedSettings));
		return true;
	}

	public static boolean isChunkWhitelisted(World world, ChunkPosc chunkPos) {
		return isChunkWhitelisted(world, chunkPos.x(), chunkPos.z());
	}

	public static boolean isChunkWhitelistedAt(World world, double x, double z) {
		return isChunkWhitelisted(world, (int) Math.floor(x / 16.0), (int) Math.floor(z / 16.0));
	}

	public static boolean canEnterUnwhitelistedChunks(Entity entity) {
		if (!(entity instanceof Player player)) {
			return false;
		}

		return player.getGamemode() == Gamemodes.CREATIVE || player.getGamemode() == Gamemodes.SPECTATOR;
	}

	public static synchronized void loadAllDimensionWhitelists(MinecraftServer server) {
		WHITELISTED_CHUNKS_BY_FILE.clear();
		DIMENSION_SETTINGS_BY_FILE.clear();
		ChunkWhitelistIO.clearWorldFileCache();
		for (WorldServer world : server.dimensionWorlds.values()) {
			try {
				loadDimensionWhitelist(world);
			} catch (IOException exception) {
				Path whitelistFile = ChunkWhitelistIO.whitelistFileFor(world);
				LOGGER.error("Unable to initialize chunk whitelist file {}", whitelistFile, exception);
				throw new UncheckedIOException("Unable to initialize chunk whitelist file " + whitelistFile, exception);
			}
		}
	}

	private static void loadDimensionWhitelist(World world) throws IOException {
		Path whitelistFile = ChunkWhitelistIO.whitelistFileFor(world);
		Set<Long> whitelistedChunks = Files.exists(whitelistFile)
			? ChunkWhitelistIO.readWhitelistedChunks(whitelistFile) : new HashSet<>();
		if (whitelistedChunks.add(packChunkPosition(0, 0)) || !Files.exists(whitelistFile)) {
			ChunkWhitelistIO.writeWhitelistedChunks(whitelistFile, whitelistedChunks);
		}
		WHITELISTED_CHUNKS_BY_FILE.put(whitelistFile, Set.copyOf(whitelistedChunks));
		boolean whitelistEnabled = initializeWhitelistSetting(world);
		LOGGER.info("Loaded {} whitelisted chunks for dimension {} from {} (whitelist {})",
			whitelistedChunks.size(), world.dimension.id, whitelistFile, whitelistEnabled ? "enabled" : "disabled");
	}

	public static synchronized List<ChunkPos> addChunks(World world, Collection<ChunkPos> chunkPositions)
		throws IOException {
		return updateChunks(world, chunkPositions, true);
	}

	public static synchronized List<ChunkPos> removeChunks(World world, Collection<ChunkPos> chunkPositions)
		throws IOException {
		return updateChunks(world, chunkPositions, false);
	}

	private static List<ChunkPos> updateChunks(
		World world, Collection<ChunkPos> chunkPositions, boolean addChunks) throws IOException {
		Path whitelistFile = ChunkWhitelistIO.whitelistFileFor(world);
		Set<Long> updatedChunks = new HashSet<>(getWhitelistedChunks(world));
		Set<Long> changedChunks = new HashSet<>();
		for (ChunkPos chunk : chunkPositions) {
			long packedPosition = packChunkPosition(chunk.x(), chunk.z());
			boolean changed = addChunks
				? updatedChunks.add(packedPosition)
				: updatedChunks.remove(packedPosition);
			if (changed) {
				changedChunks.add(packedPosition);
			}
		}
		if (changedChunks.isEmpty()) {
			return List.of();
		}
		if (changedChunks.size() > MAX_CHUNK_UPDATES_PER_COMMAND) {
			throw new IllegalArgumentException(
				"A whitelist command cannot change more than " + MAX_CHUNK_UPDATES_PER_COMMAND + " chunks.");
		}

		ChunkWhitelistIO.writeWhitelistedChunks(whitelistFile, updatedChunks);
		WHITELISTED_CHUNKS_BY_FILE.put(whitelistFile, Set.copyOf(updatedChunks));
		List<ChunkPos> changedPositions = new ArrayList<>(changedChunks.size());
		for (long packedPosition : changedChunks) {
			changedPositions.add(new ChunkPos(unpackChunkX(packedPosition), unpackChunkZ(packedPosition)));
		}
		return List.copyOf(changedPositions);
	}

	public static List<Integer> getWhitelistedChunkXCoordinates(World world) {
		Set<Integer> chunkXs = new TreeSet<>();
		for (long packedChunkPosition : getWhitelistedChunks(world)) {
			chunkXs.add(unpackChunkX(packedChunkPosition));
		}
		return List.copyOf(chunkXs);
	}

	public static List<Integer> getWhitelistedChunkZCoordinates(World world, int chunkX) {
		Set<Integer> chunkZs = new TreeSet<>();
		for (long packedChunkPosition : getWhitelistedChunks(world)) {
			if (unpackChunkX(packedChunkPosition) == chunkX) {
				chunkZs.add(unpackChunkZ(packedChunkPosition));
			}
		}
		return List.copyOf(chunkZs);
	}

	private static Set<Long> getWhitelistedChunks(World world) {
		Path whitelistFile = ChunkWhitelistIO.whitelistFileFor(world);
		return WHITELISTED_CHUNKS_BY_FILE.computeIfAbsent(whitelistFile, path -> {
			if (Files.exists(path)) {
				return Set.copyOf(ChunkWhitelistIO.readWhitelistedChunks(path));
			}
			return Set.of(packChunkPosition(0, 0));
		});
	}

	private static String dimensionKey(World world) {
		return Integer.toString(world.dimension.id);
	}

	private static Path dimensionSettingsFileFor(World world) {
		return ChunkWhitelistIO.whitelistFileFor(world).resolveSibling("dimensions.dat");
	}

	private static Map<String, Boolean> getDimensionSettings(World world) {
		Path settingsFile = dimensionSettingsFileFor(world);
		return DIMENSION_SETTINGS_BY_FILE.computeIfAbsent(settingsFile,
			path -> Map.copyOf(ChunkWhitelistIO.readDimensionSettings(path)));
	}

	private static boolean initializeWhitelistSetting(World world) throws IOException {
		Path settingsFile = dimensionSettingsFileFor(world);
		Map<String, Boolean> settings = getDimensionSettings(world);
		String key = dimensionKey(world);
		if (settings.containsKey(key)) {
			return settings.get(key);
		}

		Map<String, Boolean> updatedSettings = new HashMap<>(settings);
		updatedSettings.put(key, true);
		ChunkWhitelistIO.writeDimensionSettings(settingsFile, updatedSettings);
		DIMENSION_SETTINGS_BY_FILE.put(settingsFile, Map.copyOf(updatedSettings));
		return true;
	}

	static long packChunkPosition(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	static int unpackChunkX(long packedChunkPosition) {
		return (int) (packedChunkPosition >> 32);
	}

	static int unpackChunkZ(long packedChunkPosition) {
		return (int) packedChunkPosition;
	}
}
