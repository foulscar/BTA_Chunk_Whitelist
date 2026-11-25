package foulscar.chunkwhitelist;

import com.mojang.nbt.NbtIo;
import com.mojang.nbt.tags.CompoundTag;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
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
	private static final ConcurrentMap<Path, Set<Long>> WHITELISTED_CHUNKS_BY_FILE = new ConcurrentHashMap<>();
	private static final ConcurrentMap<Path, Map<String, Boolean>> DIMENSION_SETTINGS_BY_FILE =
		new ConcurrentHashMap<>();
	private static final ConcurrentMap<World, Path> WHITELIST_FILES_BY_WORLD = new ConcurrentHashMap<>();

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
		writeDimensionSettings(settingsFile, updatedSettings);
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
		boolean whitelistEnabled = initializeWhitelistSetting(world);
		LOGGER.info("Loaded {} whitelisted chunks for dimension {} from {} (whitelist {})",
			whitelistedChunks.size(), world.dimension.id, whitelistFile, whitelistEnabled ? "enabled" : "disabled");
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

	private static String dimensionKey(World world) {
		return Integer.toString(world.dimension.id);
	}

	private static Path dimensionSettingsFileFor(World world) {
		return whitelistFileFor(world).resolveSibling("dimensions.dat");
	}

	private static Map<String, Boolean> getDimensionSettings(World world) {
		Path settingsFile = dimensionSettingsFileFor(world);
		return DIMENSION_SETTINGS_BY_FILE.computeIfAbsent(settingsFile,
			path -> Map.copyOf(readDimensionSettings(path)));
	}

	private static Map<String, Boolean> readDimensionSettings(Path settingsFile) {
		if (!Files.exists(settingsFile)) {
			return Map.of();
		}

		try (InputStream input = Files.newInputStream(settingsFile)) {
			CompoundTag root = NbtIo.readCompressed(input);
			Map<String, Boolean> settings = new HashMap<>();
			for (String key : root.getValue().keySet()) {
				settings.put(key, root.getBoolean(key));
			}
			return settings;
		} catch (IOException exception) {
			LOGGER.error("Unable to read dimension whitelist settings {}", settingsFile, exception);
			throw new UncheckedIOException("Unable to read dimension whitelist settings " + settingsFile, exception);
		}
	}

	private static boolean initializeWhitelistSetting(World world) throws IOException {
		Path settingsFile = dimensionSettingsFileFor(world);
		Map<String, Boolean> settings = getDimensionSettings(world);
		String key = dimensionKey(world);
		Path legacyFile = whitelistFileFor(world).resolveSibling(key + ".enabled");
		if (settings.containsKey(key)) {
			Files.deleteIfExists(legacyFile);
			return settings.get(key);
		}

		boolean enabled = readLegacyWhitelistSetting(legacyFile);
		Map<String, Boolean> updatedSettings = new HashMap<>(settings);
		updatedSettings.put(key, enabled);
		writeDimensionSettings(settingsFile, updatedSettings);
		DIMENSION_SETTINGS_BY_FILE.put(settingsFile, Map.copyOf(updatedSettings));
		Files.deleteIfExists(legacyFile);
		return enabled;
	}

	private static boolean readLegacyWhitelistSetting(Path legacyFile) throws IOException {
		if (!Files.exists(legacyFile)) {
			return true;
		}

		String value = Files.readString(legacyFile, StandardCharsets.UTF_8).trim();
		if (value.equalsIgnoreCase("true")) {
			return true;
		}
		if (value.equalsIgnoreCase("false")) {
			return false;
		}
		throw new IOException("Invalid legacy whitelist setting in " + legacyFile + ": " + value);
	}

	private static void writeDimensionSettings(Path settingsFile, Map<String, Boolean> settings) throws IOException {
		Path parent = settingsFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		Path temporaryFile = settingsFile.resolveSibling(settingsFile.getFileName() + ".tmp");
		CompoundTag root = new CompoundTag();
		for (Map.Entry<String, Boolean> entry : settings.entrySet()) {
			root.putBoolean(entry.getKey(), entry.getValue());
		}
		try {
			try (OutputStream output = Files.newOutputStream(temporaryFile,
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
				NbtIo.writeCompressed(root, output);
			}
			try {
				Files.move(temporaryFile, settingsFile, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException exception) {
				Files.move(temporaryFile, settingsFile, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temporaryFile);
		}
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
