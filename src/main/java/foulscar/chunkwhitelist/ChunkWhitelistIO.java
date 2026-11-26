package foulscar.chunkwhitelist;

import com.mojang.nbt.NbtIo;
import com.mojang.nbt.tags.CompoundTag;
import net.minecraft.core.world.World;

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

final class ChunkWhitelistIO {
	private static final ConcurrentMap<World, Path> WHITELIST_FILES_BY_WORLD = new ConcurrentHashMap<>();

	private ChunkWhitelistIO() {
	}

	static void clearWorldFileCache() {
		WHITELIST_FILES_BY_WORLD.clear();
	}

	static Path whitelistFileFor(World world) {
		return WHITELIST_FILES_BY_WORLD.computeIfAbsent(world, key -> {
			String whitelistFileName = key.dimension.id + ".csv";
			return key.getLevelStorage().getDataFile("chunkwhitelist/" + whitelistFileName)
				.toPath()
				.resolveSibling(whitelistFileName)
				.toAbsolutePath()
				.normalize();
		});
	}

	static Set<Long> readWhitelistedChunks(Path whitelistFile) {
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
					whitelistedChunks.add(ChunkWhitelistStorage.packChunkPosition(chunkX, chunkZ));
				} catch (NumberFormatException exception) {
					if (i == 0 && line.equalsIgnoreCase("chunk_x,chunk_z")) {
						continue;
					}
					ChunkWhitelist.LOGGER.warn("Ignoring invalid chunk whitelist entry at {}:{}: {}",
						whitelistFile, i + 1, line);
				}
			}
		} catch (IOException exception) {
			ChunkWhitelist.LOGGER.error("Unable to read chunk whitelist file {}", whitelistFile, exception);
			throw new UncheckedIOException("Unable to read chunk whitelist file " + whitelistFile, exception);
		}
		return whitelistedChunks;
	}

	static void writeWhitelistedChunks(Path whitelistFile, Set<Long> whitelistedChunks) throws IOException {
		List<String> lines = new ArrayList<>();
		lines.add("chunk_x,chunk_z");
		for (long packedChunkPosition : new TreeSet<>(whitelistedChunks)) {
			lines.add(ChunkWhitelistStorage.unpackChunkX(packedChunkPosition) + ","
				+ ChunkWhitelistStorage.unpackChunkZ(packedChunkPosition));
		}

		Path parent = whitelistFile.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		Files.write(whitelistFile, lines, StandardCharsets.UTF_8,
			StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
	}

	static Map<String, Boolean> readDimensionSettings(Path settingsFile) {
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
			ChunkWhitelist.LOGGER.error("Unable to read dimension whitelist settings {}", settingsFile, exception);
			throw new UncheckedIOException("Unable to read dimension whitelist settings " + settingsFile, exception);
		}
	}

	static void writeDimensionSettings(Path settingsFile, Map<String, Boolean> settings) throws IOException {
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
}
