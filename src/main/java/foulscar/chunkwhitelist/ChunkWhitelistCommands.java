package foulscar.chunkwhitelist;

import com.mojang.brigadier.arguments.ArgumentTypeInteger;
import com.mojang.brigadier.builder.ArgumentBuilderLiteral;
import com.mojang.brigadier.builder.ArgumentBuilderRequired;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import foulscar.chunkwhitelist.mixin.PlayerInstanceAccessor;
import foulscar.chunkwhitelist.mixin.PlayerManagerAccessor;
import net.minecraft.core.net.command.CommandManager;
import net.minecraft.core.net.command.CommandSource;
import net.minecraft.core.world.World;
import net.minecraft.core.world.chunk.ChunkCoordinate;
import net.minecraft.core.world.pos.ChunkPos;
import net.minecraft.server.entity.player.PlayerServer;
import net.minecraft.server.net.PlayerHash;
import net.minecraft.server.net.command.ServerCommandSource;
import net.minecraft.server.player.PlayerManager;
import net.minecraft.server.world.WorldServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;

public final class ChunkWhitelistCommands {
	private static final int MAX_RADIUS = 36;

	private ChunkWhitelistCommands() {
	}

	public static void registerCommands() {
		CommandManager.registerServerCommand(dispatcher ->
			dispatcher.register(ArgumentBuilderLiteral.<CommandSource>literal("chunk")
				.then(createTeleportCommand().requires(CommandSource::hasAdmin))
				.then(ArgumentBuilderLiteral.<CommandSource>literal("whitelist")
					.executes(ChunkWhitelistCommands::showUsage)
					.then(createDimensionIdArgument()
						.executes(ChunkWhitelistCommands::showWhitelistStatus)
						.then(createWhitelistEditCommand("add", WhitelistEditAction.ADD))
						.then(createWhitelistEditCommand("remove", WhitelistEditAction.REMOVE))
						.then(createWhitelistToggleCommand("enable", true))
						.then(createWhitelistToggleCommand("disable", false))
						.then(ArgumentBuilderLiteral.<CommandSource>literal("status")
							.executes(ChunkWhitelistCommands::showWhitelistStatus))
						.then(ArgumentBuilderLiteral.<CommandSource>literal("list")
							.executes(ChunkWhitelistCommands::listSubscribedWhitelistedChunks))))));
	}

	private static ArgumentBuilderLiteral<CommandSource> createWhitelistEditCommand(
		String name, WhitelistEditAction action) {
		return ArgumentBuilderLiteral.<CommandSource>literal(name)
			.requires(CommandSource::hasAdmin)
			.then(ArgumentBuilderLiteral.<CommandSource>literal("single")
				.then(ArgumentBuilderRequired.<CommandSource, Integer>argument("x", ArgumentTypeInteger.integer())
					.suggests(ChunkWhitelistCommandSuggestions::suggestCurrentChunkX)
					.then(ArgumentBuilderRequired.<CommandSource, Integer>argument("z", ArgumentTypeInteger.integer())
						.suggests(ChunkWhitelistCommandSuggestions::suggestCurrentChunkZ)
						.executes(context -> changeSingleChunk(context, action)))))
			.then(ArgumentBuilderLiteral.<CommandSource>literal("rect")
				.then(ArgumentBuilderRequired.<CommandSource, Integer>argument("x1", ArgumentTypeInteger.integer())
					.suggests(ChunkWhitelistCommandSuggestions::suggestCurrentChunkX)
					.then(ArgumentBuilderRequired.<CommandSource, Integer>argument("z1", ArgumentTypeInteger.integer())
						.suggests(ChunkWhitelistCommandSuggestions::suggestCurrentChunkZ)
						.then(ArgumentBuilderRequired.<CommandSource, Integer>argument("x2", ArgumentTypeInteger.integer())
							.suggests(ChunkWhitelistCommandSuggestions::suggestCurrentChunkX)
							.then(ArgumentBuilderRequired.<CommandSource, Integer>argument("z2", ArgumentTypeInteger.integer())
								.suggests(ChunkWhitelistCommandSuggestions::suggestCurrentChunkZ)
								.executes(context -> changeRectangle(context, action)))))))
			.then(ArgumentBuilderLiteral.<CommandSource>literal("radius")
				.then(ArgumentBuilderRequired.<CommandSource, Integer>argument(
						"radius", ArgumentTypeInteger.integer(0))
					.executes(context -> changeRadius(context, action))));
	}

	private static ArgumentBuilderLiteral<CommandSource> createTeleportCommand() {
		return ArgumentBuilderLiteral.<CommandSource>literal("tp")
			.then(ArgumentBuilderRequired.<CommandSource, Integer>argument(
					"chunkX", ArgumentTypeInteger.integer())
				.suggests(ChunkWhitelistCommandSuggestions::suggestWhitelistedChunkX)
				.then(ArgumentBuilderRequired.<CommandSource, Integer>argument(
						"chunkZ", ArgumentTypeInteger.integer())
					.suggests(ChunkWhitelistCommandSuggestions::suggestWhitelistedChunkZ)
					.executes(ChunkWhitelistCommands::teleportToChunk)));
	}

	private static ArgumentBuilderLiteral<CommandSource> createWhitelistToggleCommand(String name, boolean enabled) {
		return ArgumentBuilderLiteral.<CommandSource>literal(name)
			.requires(CommandSource::hasAdmin)
			.executes(context -> setWhitelistEnabled(context, enabled));
	}

	private static ArgumentBuilderRequired<CommandSource, Integer> createDimensionIdArgument() {
		return ArgumentBuilderRequired.<CommandSource, Integer>argument("dimensionId", ArgumentTypeInteger.integer())
			.suggests(ChunkWhitelistCommandSuggestions::suggestDimensionIds);
	}

	private static int showUsage(CommandContext<CommandSource> context) {
		context.getSource().sendMessage("Usage: /chunk tp <chunkX> <chunkZ>");
		context.getSource().sendMessage("Usage: /chunk whitelist <dimensionId> add|remove single <x> <z>");
		context.getSource().sendMessage("       /chunk whitelist <dimensionId> add|remove rect <x1> <z1> <x2> <z2>");
		context.getSource().sendMessage("       /chunk whitelist <dimensionId> add|remove radius <number>");
		context.getSource().sendMessage("       /chunk whitelist <dimensionId> enable|disable|status|list");
		context.getSource().sendMessage(
			"Radius edits are centered on your current chunk and require you to be in that dimension.");
		return 1;
	}

	private static int teleportToChunk(CommandContext<CommandSource> context) {
		CommandSource commandSource = context.getSource();
		if (!(commandSource instanceof ServerCommandSource source) || source.player == null) {
			commandSource.sendMessage("/chunk tp can only be used by an operator in-game.");
			return 0;
		}

		World sourceWorld = source.getWorld();
		if (!(sourceWorld instanceof WorldServer world)) {
			commandSource.sendMessage("Could not access the current server dimension.");
			return 0;
		}

		int chunkX = ArgumentTypeInteger.getInteger(context, "chunkX");
		int chunkZ = ArgumentTypeInteger.getInteger(context, "chunkZ");
		if (!ChunkWhitelistStorage.isChunkWhitelisted(world, chunkX, chunkZ)) {
			commandSource.sendMessage("Chunk " + chunkX + " " + chunkZ + " is not whitelisted.");
			return 0;
		}

		world.getChunkProvider().prepareChunk(chunkX, chunkZ);
		int blockX = chunkX * 16 + 8;
		int blockZ = chunkZ * 16 + 8;
		int y = Math.max(1, world.findTopSolidNonLiquidBlock(blockX, blockZ));
		source.player.teleport(blockX + 0.5, y, blockZ + 0.5, source.player.yRot, source.player.xRot);
		commandSource.sendMessage("Teleported to chunk " + chunkX + " " + chunkZ + ".");
		return 1;
	}

	private static int listSubscribedWhitelistedChunks(CommandContext<CommandSource> context) {
		CommandSource commandSource = context.getSource();
		if (!(commandSource instanceof ServerCommandSource source) || source.player == null) {
			commandSource.sendMessage("/chunk whitelist list can only be used by a player.");
			return 0;
		}

		PlayerServer player = source.player;
		int dimensionId = context.getArgument("dimensionId", Integer.class);
		if (player.dimension != dimensionId) {
			commandSource.sendMessage("You must be in dimension " + dimensionId + " to list its subscribed chunks.");
			return 0;
		}
		WorldServer world = getLoadedDimension(source, dimensionId);
		if (world == null) {
			commandSource.sendMessage("Dimension " + dimensionId + " is not loaded.");
			return 0;
		}

		PlayerManager playerManager = player.mcServer.playerList.getPlayerManager(dimensionId);
		if (playerManager == null) {
			commandSource.sendMessage("Could not read the chunk subscriptions for this dimension.");
			return 0;
		}

		PlayerManagerAccessor managerAccessor = (PlayerManagerAccessor) playerManager;
		PlayerHash chunkWatchers = managerAccessor.chunkWhitelist$getPlayerInstances();
		int playerChunkX = (int) player.x >> 4;
		int playerChunkZ = (int) player.z >> 4;
		int viewRadius = managerAccessor.chunkWhitelist$getViewRadius();
		TreeSet<ChunkCoordinate> subscribedWhitelistedChunks = new TreeSet<>(
			Comparator.comparingInt((ChunkCoordinate chunk) -> chunk.x).thenComparingInt(chunk -> chunk.z));
		for (int chunkX = playerChunkX - viewRadius; chunkX <= playerChunkX + viewRadius; chunkX++) {
			for (int chunkZ = playerChunkZ - viewRadius; chunkZ <= playerChunkZ + viewRadius; chunkZ++) {
				long watcherKey = ChunkWhitelistChunkRefresher.getChunkWatcherKey(chunkX, chunkZ);
				Object chunkWatcher = chunkWatchers.getValueByKey(watcherKey);
				if (chunkWatcher != null
					&& ((PlayerInstanceAccessor) chunkWatcher).chunkWhitelist$getPlayers().contains(player)
					&& ChunkWhitelistStorage.isChunkWhitelisted(world, chunkX, chunkZ)) {
					subscribedWhitelistedChunks.add(new ChunkCoordinate(chunkX, chunkZ));
				}
			}
		}
		commandSource.sendMessage("Subscribed to " + subscribedWhitelistedChunks.size()
			+ " whitelisted chunks in dimension " + player.dimension + ":");

		List<String> coordinates = new ArrayList<>(subscribedWhitelistedChunks.size());
		for (ChunkCoordinate chunk : subscribedWhitelistedChunks) {
			coordinates.add(chunk.x + "," + chunk.z);
		}
		for (int start = 0; start < coordinates.size(); start += 16) {
			int end = Math.min(start + 16, coordinates.size());
			commandSource.sendMessage(String.join("  ", coordinates.subList(start, end)));
		}
		return 1;
	}

	private static int setWhitelistEnabled(CommandContext<CommandSource> context, boolean enabled) {
		CommandSource commandSource = context.getSource();
		if (!(commandSource instanceof ServerCommandSource source)) {
			commandSource.sendMessage("The chunk whitelist can only be changed on the server.");
			return 0;
		}
		int dimensionId = context.getArgument("dimensionId", Integer.class);
		WorldServer world = source.server.dimensionWorlds.get(dimensionId);
		if (world == null) {
			commandSource.sendMessage("Dimension " + dimensionId + " is not loaded.");
			return 0;
		}

		boolean changed;
		try {
			changed = ChunkWhitelistStorage.setWhitelistEnabled(world, enabled);
		} catch (IOException exception) {
			ChunkWhitelist.LOGGER.error("Unable to save the chunk whitelist setting for dimension {}",
				world.dimension.id, exception);
			commandSource.sendMessage("Could not save the chunk whitelist setting. Check the server log for details.");
			return 0;
		}

		if (changed) {
			ChunkWhitelistChunkRefresher.refreshLoadedChunksForWhitelistState(world, enabled);
		}
		commandSource.sendMessage("Chunk whitelist is " + (enabled ? "enabled" : "disabled")
			+ " in dimension " + world.dimension.id + (changed ? "." : " (no change)."));
		return 1;
	}

	private static int showWhitelistStatus(CommandContext<CommandSource> context) {
		CommandSource commandSource = context.getSource();
		if (!(commandSource instanceof ServerCommandSource source)) {
			commandSource.sendMessage("Could not read the chunk whitelist status on this source.");
			return 0;
		}
		int dimensionId = context.getArgument("dimensionId", Integer.class);
		WorldServer world = source.server.dimensionWorlds.get(dimensionId);
		if (world == null) {
			commandSource.sendMessage("Dimension " + dimensionId + " is not loaded.");
			return 0;
		}

		commandSource.sendMessage("Chunk whitelist is "
			+ (ChunkWhitelistStorage.isWhitelistEnabled(world) ? "enabled" : "disabled")
			+ " in dimension " + world.dimension.id + ".");
		return 1;
	}

	private static int changeSingleChunk(CommandContext<CommandSource> context, WhitelistEditAction action) {
		int chunkX = context.getArgument("x", Integer.class);
		int chunkZ = context.getArgument("z", Integer.class);
		return updateChunks(context, List.of(new ChunkPos(chunkX, chunkZ)), action);
	}

	private static int changeRectangle(CommandContext<CommandSource> context, WhitelistEditAction action) {
		int firstX = context.getArgument("x1", Integer.class);
		int firstZ = context.getArgument("z1", Integer.class);
		int secondX = context.getArgument("x2", Integer.class);
		int secondZ = context.getArgument("z2", Integer.class);
		long minX = Math.min(firstX, secondX);
		long maxX = Math.max(firstX, secondX);
		long minZ = Math.min(firstZ, secondZ);
		long maxZ = Math.max(firstZ, secondZ);
		long width = maxX - minX + 1;
		long depth = maxZ - minZ + 1;
		if (width > ChunkWhitelistStorage.MAX_CHUNK_UPDATES_PER_COMMAND
			|| depth > ChunkWhitelistStorage.MAX_CHUNK_UPDATES_PER_COMMAND
			|| width * depth > ChunkWhitelistStorage.MAX_CHUNK_UPDATES_PER_COMMAND) {
			context.getSource().sendMessage("A whitelist command cannot change more than "
				+ ChunkWhitelistStorage.MAX_CHUNK_UPDATES_PER_COMMAND + " chunks.");
			return 0;
		}

		List<ChunkPos> chunks = new ArrayList<>((int) (width * depth));
		for (long chunkX = minX; chunkX <= maxX; chunkX++) {
			for (long chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
				chunks.add(new ChunkPos((int) chunkX, (int) chunkZ));
			}
		}
		return updateChunks(context, chunks, action);
	}

	private static int changeRadius(CommandContext<CommandSource> context, WhitelistEditAction action) {
		int radius = context.getArgument("radius", Integer.class);
		if (radius > MAX_RADIUS) {
			context.getSource().sendMessage("Radius cannot exceed " + MAX_RADIUS
				+ " chunks (maximum " + ChunkWhitelistStorage.MAX_CHUNK_UPDATES_PER_COMMAND
				+ " chunks per command).");
			return 0;
		}
		if (!(context.getSource() instanceof ServerCommandSource source) || source.player == null) {
			context.getSource().sendMessage("Radius edits can only be used by a player in the target dimension.");
			return 0;
		}

		int dimensionId = context.getArgument("dimensionId", Integer.class);
		if (source.player.dimension != dimensionId) {
			context.getSource().sendMessage("You must be in dimension " + dimensionId + " to use a radius edit.");
			return 0;
		}

		int centerX = (int) Math.floor(source.player.x / 16.0);
		int centerZ = (int) Math.floor(source.player.z / 16.0);
		long radiusSquared = (long) radius * radius;
		List<ChunkPos> chunks = new ArrayList<>();
		for (int offsetX = -radius; offsetX <= radius; offsetX++) {
			for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
				if ((long) offsetX * offsetX + (long) offsetZ * offsetZ <= radiusSquared) {
					chunks.add(new ChunkPos(centerX + offsetX, centerZ + offsetZ));
				}
			}
		}
		return updateChunks(context, chunks, action);
	}

	private static int updateChunks(
		CommandContext<CommandSource> context, List<ChunkPos> chunks, WhitelistEditAction action) {
		CommandSource commandSource = context.getSource();
		if (!(commandSource instanceof ServerCommandSource source)) {
			commandSource.sendMessage("The chunk whitelist can only be changed on the server.");
			return 0;
		}

		int dimensionId = context.getArgument("dimensionId", Integer.class);
		WorldServer world = getLoadedDimension(source, dimensionId);
		if (world == null) {
			commandSource.sendMessage("Dimension " + dimensionId + " is not loaded.");
			return 0;
		}

		List<ChunkPos> changedChunks;
		try {
			changedChunks = action == WhitelistEditAction.ADD
				? ChunkWhitelistStorage.addChunks(world, chunks)
				: ChunkWhitelistStorage.removeChunks(world, chunks);
		} catch (IllegalArgumentException exception) {
			commandSource.sendMessage(exception.getMessage());
			return 0;
		} catch (IOException exception) {
			ChunkWhitelist.LOGGER.error("Unable to save the chunk whitelist for dimension {}",
				world.dimension.id, exception);
			commandSource.sendMessage("Could not save the chunk whitelist. Check the server log for details.");
			return 0;
		}

		if (!changedChunks.isEmpty() && ChunkWhitelistStorage.isWhitelistEnabled(world)) {
			ChunkWhitelistChunkRefresher.refreshChangedChunks(world, changedChunks);
		}
		commandSource.sendMessage((action == WhitelistEditAction.ADD ? "Added " : "Removed ") + changedChunks.size()
			+ " chunks in dimension " + dimensionId + ".");
		return 1;
	}

	private static WorldServer getLoadedDimension(ServerCommandSource source, int dimensionId) {
		return source.server.dimensionWorlds.get(dimensionId);
	}

	private enum WhitelistEditAction {
		ADD,
		REMOVE
	}
}
