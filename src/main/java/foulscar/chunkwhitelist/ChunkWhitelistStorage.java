package foulscar.chunkwhitelist;

import net.minecraft.core.world.World;
import net.minecraft.core.world.pos.ChunkPos;
import net.minecraft.core.world.pos.ChunkPosc;
import net.minecraft.core.entity.Entity;
import net.minecraft.core.entity.player.Player;
import net.minecraft.core.player.gamemode.Gamemodes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.WorldServer;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ChunkWhitelistStorage {
	private static final Logger LOGGER = ChunkWhitelist.LOGGER;
	private static final ConcurrentMap<Path, Set<Long>> WHITELISTED_CHUNKS_BY_FILE = new ConcurrentHashMap<>();
	private static final ConcurrentMap<World, Path> WHITELIST_FILES_BY_WORLD = new ConcurrentHashMap<>();

	private ChunkWhitelistStorage() {
	}

	public static boolean isChunkWhitelisted(World world, int chunkX, int chunkZ) {
		return getWhitelistedChunks(world).contains(packChunkPosition(chunkX, chunkZ));
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
		WHITELIST_FILES_BY_WORLD.clear();
		for (WorldServer world : server.dimensionWorlds.values()) {
			try {
				loadDimensionWhitelist(world);
			} catch (IOException exception) {
				Path whitelistFile = whitelistFileFor(world);
				LOGGER.error("Unable to initialize chunk whitelist file {}", whitelistFile, exception);
				throw new UncheckedIOException("Unable to initialize chunk whitelist file " + whitelistFile, exception);
			}
		}
	}

	private static void loadDimensionWhitelist(World world) throws IOException {
		Path whitelistFile = whitelistFileFor(world);
		Set<Long> whitelistedChunks = Files.exists(whitelistFile)
			? readWhitelistedChunks(whitelistFile) : new HashSet<>();
		if (whitelistedChunks.add(packChunkPosition(0, 0)) || !Files.exists(whitelistFile)) {
			writeWhitelistedChunks(whitelistFile, whitelistedChunks);
		}
		WHITELISTED_CHUNKS_BY_FILE.put(whitelistFile, Set.copyOf(whitelistedChunks));
		LOGGER.info("Loaded {} whitelisted chunks for dimension {} from {}",
			whitelistedChunks.size(), world.dimension.id, whitelistFile);
	}

	public static synchronized boolean addChunk(World world, int chunkX, int chunkZ) throws IOException {
		Path whitelistFile = whitelistFileFor(world);
		Set<Long> whitelistedChunks = new HashSet<>(getWhitelistedChunks(world));
		if (!whitelistedChunks.add(packChunkPosition(chunkX, chunkZ))) {
			return false;
		}

		writeWhitelistedChunks(whitelistFile, whitelistedChunks);
		WHITELISTED_CHUNKS_BY_FILE.put(whitelistFile, Set.copyOf(whitelistedChunks));
		return true;
	}

	public static synchronized boolean removeChunk(World world, int chunkX, int chunkZ) throws IOException {
		Path whitelistFile = whitelistFileFor(world);
		Set<Long> whitelistedChunks = new HashSet<>(getWhitelistedChunks(world));
		if (!whitelistedChunks.remove(packChunkPosition(chunkX, chunkZ))) {
			return false;
		}

		writeWhitelistedChunks(whitelistFile, whitelistedChunks);
		WHITELISTED_CHUNKS_BY_FILE.put(whitelistFile, Set.copyOf(whitelistedChunks));
		return true;
	}

	public static List<ChunkPos> getWhitelistedChunkPositions(World world) {
		List<ChunkPos> chunkPositions = new ArrayList<>();
		for (long packedChunkPosition : new TreeSet<>(getWhitelistedChunks(world))) {
			chunkPositions.add(new ChunkPos(unpackChunkX(packedChunkPosition), unpackChunkZ(packedChunkPosition)));
		}
		return List.copyOf(chunkPositions);
	}

	private static void writeWhitelistedChunks(Path whitelistFile, Set<Long> whitelistedChunks) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("chunk_x,chunk_z");
		for (long packedChunkPosition : new TreeSet<>(whitelistedChunks)) {
			lines.add(unpackChunkX(packedChunkPosition) + "," + unpackChunkZ(packedChunkPosition));
		}

		Path parent = whitelistFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(whitelistFile, lines, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
	}

	private static Set<Long> getWhitelistedChunks(World world) {
		Path whitelistFile = whitelistFileFor(world);
		return WHITELISTED_CHUNKS_BY_FILE.computeIfAbsent(whitelistFile, path -> {
			if (Files.exists(path)) {
				return Set.copyOf(readWhitelistedChunks(path));
			}
			return Set.of(packChunkPosition(0, 0));
		});
	}

	private static Set<Long> readWhitelistedChunks(Path whitelistFile) {
		Set<Long> whitelistedChunks = new HashSet<>();
		if (!Files.exists(whitelistFile)) {
			return whitelistedChunks;
		}

		try {
			List<String> lines = Files.readAllLines(whitelistFile, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i).trim();
				if (line.isEmpty() || line.startsWith("#")) {
					continue;
				}

				String[] coordinates = line.split(",", -1);
				try {
					if (coordinates.length != 2) {
						throw new NumberFormatException("expected two coordinates");
					}
					int chunkX = Integer.parseInt(coordinates[0].trim());
					int chunkZ = Integer.parseInt(coordinates[1].trim());
					whitelistedChunks.add(packChunkPosition(chunkX, chunkZ));
				} catch (NumberFormatException exception) {
					if (i == 0 && line.equalsIgnoreCase("chunk_x,chunk_z")) {
						continue;
					}
					LOGGER.warn("Ignoring invalid chunk whitelist entry at {}:{}: {}",
						whitelistFile, i + 1, line);
				}
			}
		} catch (IOException exception) {
			LOGGER.error("Unable to read chunk whitelist file {}", whitelistFile, exception);
			throw new UncheckedIOException("Unable to read chunk whitelist file " + whitelistFile, exception);
		}
		return whitelistedChunks;
	}

	private static Path whitelistFileFor(World world) {
		return WHITELIST_FILES_BY_WORLD.computeIfAbsent(world, key -> {
			String whitelistFileName = key.dimension.id + ".csv";
			return key.getLevelStorage().getDataFile("chunkwhitelist/" + whitelistFileName)
				.toPath()
				.resolveSibling(whitelistFileName)
				.toAbsolutePath()
				.normalize();
		});
	}

	private static long packChunkPosition(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	private static int unpackChunkX(long packedChunkPosition) {
		return (int) (packedChunkPosition >> 32);
	}

	private static int unpackChunkZ(long packedChunkPosition) {
		return (int) packedChunkPosition;
	}
}
