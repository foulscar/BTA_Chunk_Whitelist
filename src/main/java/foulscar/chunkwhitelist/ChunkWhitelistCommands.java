package foulscar.chunkwhitelist;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.ArgumentTypeInteger;
import com.mojang.brigadier.builder.ArgumentBuilderLiteral;
import com.mojang.brigadier.builder.ArgumentBuilderRequired;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import foulscar.chunkwhitelist.mixin.ChunkProviderServerAccessor;
import foulscar.chunkwhitelist.mixin.PlayerInstanceAccessor;
import foulscar.chunkwhitelist.mixin.PlayerManagerAccessor;
import net.minecraft.core.net.command.CommandManager;
import net.minecraft.core.net.command.CommandSource;
import net.minecraft.core.world.World;
import net.minecraft.core.world.chunk.Chunk;
import net.minecraft.core.world.chunk.ChunkCoordinate;
import net.minecraft.core.world.pos.ChunkPos;
import net.minecraft.core.world.pos.TilePosc;
import net.minecraft.server.entity.player.PlayerServer;
import net.minecraft.server.net.PlayerHash;
import net.minecraft.server.net.command.ServerCommandSource;
import net.minecraft.server.player.PlayerManager;
import net.minecraft.server.world.WorldServer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

public final class ChunkWhitelistCommands {
	private ChunkWhitelistCommands() {
	}

	public static void registerCommands() {
		CommandManager.registerServerCommand(dispatcher ->
			dispatcher.register(ArgumentBuilderLiteral.<CommandSource>literal("chunk")
				.then(createTeleportCommand().requires(CommandSource::hasAdmin))
				.then(ArgumentBuilderLiteral.<CommandSource>literal("whitelist")
					.executes(ChunkWhitelistCommands::showUsage)
					.then(createWhitelistCommand("add", WhitelistAction.ADD).requires(CommandSource::hasAdmin))
					.then(createWhitelistCommand("remove", WhitelistAction.REMOVE).requires(CommandSource::hasAdmin))
					.then(createWhitelistToggleCommand("enable", true))
					.then(createWhitelistToggleCommand("disable", false))
					.then(ArgumentBuilderLiteral.<CommandSource>literal("status")
						.then(createDimensionIdArgument()
							.executes(ChunkWhitelistCommands::showWhitelistStatus)))
					.then(ArgumentBuilderLiteral.<CommandSource>literal("list")
						.executes(ChunkWhitelistCommands::listSubscribedWhitelistedChunks)))));
	}

	private static ArgumentBuilderLiteral<CommandSource> createWhitelistCommand(String name, WhitelistAction action) {
		return ArgumentBuilderLiteral.<CommandSource>literal(name)
			.executes(context -> changeCurrentChunk(context, action))
			.then(ArgumentBuilderRequired.<CommandSource, Integer>argument(
					"chunkX", ArgumentTypeInteger.integer())
				.suggests(action == WhitelistAction.ADD
					? ChunkWhitelistCommands::suggestCurrentChunkX : ChunkWhitelistCommands::suggestWhitelistedChunkX)
				.then(ArgumentBuilderRequired.<CommandSource, Integer>argument(
						"chunkZ", ArgumentTypeInteger.integer())
					.suggests(action == WhitelistAction.ADD
						? ChunkWhitelistCommands::suggestCurrentChunkZ : ChunkWhitelistCommands::suggestWhitelistedChunkZ)
					.executes(context -> changeChunkAtCoordinates(context, action))));
	}

	private static ArgumentBuilderLiteral<CommandSource> createTeleportCommand() {
		return ArgumentBuilderLiteral.<CommandSource>literal("tp")
			.then(ArgumentBuilderRequired.<CommandSource, Integer>argument(
					"chunkX", ArgumentTypeInteger.integer())
				.suggests(ChunkWhitelistCommands::suggestWhitelistedChunkX)
				.then(ArgumentBuilderRequired.<CommandSource, Integer>argument(
						"chunkZ", ArgumentTypeInteger.integer())
					.suggests(ChunkWhitelistCommands::suggestWhitelistedChunkZ)
					.executes(ChunkWhitelistCommands::teleportToChunk)));
	}

	private static ArgumentBuilderLiteral<CommandSource> createWhitelistToggleCommand(String name, boolean enabled) {
		return ArgumentBuilderLiteral.<CommandSource>literal(name)
			.requires(CommandSource::hasAdmin)
			.then(createDimensionIdArgument()
				.executes(context -> setWhitelistEnabled(context, enabled)));
	}

	private static ArgumentBuilderRequired<CommandSource, Integer> createDimensionIdArgument() {
		return ArgumentBuilderRequired.<CommandSource, Integer>argument("dimensionId", ArgumentTypeInteger.integer())
			.suggests(ChunkWhitelistCommands::suggestDimensionIds);
	}

	private static int showUsage(CommandContext<CommandSource> context) {
		context.getSource().sendMessage("Usage: /chunk tp <chunkX> <chunkZ>");
		context.getSource().sendMessage("Usage: /chunk whitelist add [chunkX] [chunkZ]");
		context.getSource().sendMessage("       /chunk whitelist remove [chunkX] [chunkZ]");
		context.getSource().sendMessage("       /chunk whitelist enable|disable <dimensionId>");
		context.getSource().sendMessage("       /chunk whitelist status <dimensionId>");
		context.getSource().sendMessage("       /chunk whitelist list");
		context.getSource().sendMessage(
			"Without coordinates, the command uses your current chunk. Coordinate commands also work from the console.");
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
		World world = source.getWorld();
		if (!(world instanceof WorldServer)) {
			commandSource.sendMessage("Could not read the chunk whitelist for this dimension.");
			return 0;
		}

		PlayerManager playerManager = player.mcServer.playerList.getPlayerManager(player.dimension);
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
				long watcherKey = getChunkWatcherKey(chunkX, chunkZ);
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
			refreshLoadedChunksForWhitelistState(world, enabled);
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

	private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestDimensionIds(
		CommandContext<CommandSource> context, SuggestionsBuilder builder) {
		if (context.getSource() instanceof ServerCommandSource source) {
			source.server.dimensionWorlds.int2ObjectEntrySet().forEach(entry ->
				builder.suggest(entry.getIntKey(),
					new LiteralMessage(entry.getValue().dimension.getTranslatedName())));
		}
		return builder.buildFuture();
	}

	private static void refreshLoadedChunksForWhitelistState(WorldServer world, boolean enabled) {
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

	private static long getChunkWatcherKey(int chunkX, int chunkZ) {
		return (((long) chunkZ + Integer.MAX_VALUE) << 32) | ((long) chunkX + Integer.MAX_VALUE);
	}

	private static int changeCurrentChunk(
		CommandContext<CommandSource> context, WhitelistAction action) throws CommandSyntaxException {
		ServerCommandSource source = (ServerCommandSource) context.getSource();
		if (source.player == null) {
			source.sendMessage("From the console, specify chunk coordinates: /chunk whitelist "
				+ action.commandName + " <chunkX> <chunkZ>");
			return 0;
		}

		TilePosc blockPosition = source.getBlockCoordinates();
		return changeChunkWhitelist(source, blockPosition.x() >> 4, blockPosition.z() >> 4, action);
	}

	private static int changeChunkAtCoordinates(
		CommandContext<CommandSource> context, WhitelistAction action) throws CommandSyntaxException {
		ServerCommandSource source = (ServerCommandSource) context.getSource();
		int chunkX = ArgumentTypeInteger.getInteger(context, "chunkX");
		int chunkZ = ArgumentTypeInteger.getInteger(context, "chunkZ");
		return changeChunkWhitelist(source, chunkX, chunkZ, action);
	}

	private static int changeChunkWhitelist(ServerCommandSource source, int chunkX, int chunkZ, WhitelistAction action) {
		World sourceWorld = source.getWorld();
		if (!(sourceWorld instanceof WorldServer world)) {
			source.sendMessage("The chunk whitelist can only be changed on the server.");
			return 0;
		}

		boolean changed;
		try {
			changed = action == WhitelistAction.ADD
				? ChunkWhitelistStorage.addChunk(world, chunkX, chunkZ)
				: ChunkWhitelistStorage.removeChunk(world, chunkX, chunkZ);
		} catch (IOException exception) {
			ChunkWhitelist.LOGGER.error("Unable to save the chunk whitelist for dimension {}", world.dimension.id, exception);
			source.sendMessage("Could not save the chunk whitelist. Check the server log for details.");
			return 0;
		}

		if (changed) {
			world.getChunkProvider().prepareChunk(new ChunkPos(chunkX, chunkZ), true);
			refreshPlayerChunkWatchers(world);
		}
		String resultMessage = action == WhitelistAction.ADD
			? (changed ? "Added " : "Already contains ")
			: (changed ? "Removed " : "Does not contain ");
		source.sendMessage(resultMessage + "chunk " + chunkX + " " + chunkZ + " in dimension " + world.dimension.id + ".");
		return 1;
	}

	private static void refreshPlayerChunkWatchers(WorldServer world) {
		PlayerManager playerManager = world.mcServer.playerList.getPlayerManager(world.dimension.id);
		if (playerManager == null) {
			return;
		}

		for (PlayerServer player : List.copyOf(playerManager.players)) {
			playerManager.removePlayer(player);
			playerManager.addPlayer(player);
		}
	}

	private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestCurrentChunkX(
		CommandContext<CommandSource> context, SuggestionsBuilder builder) {
		return suggestCurrentChunkCoordinate(context.getSource(), builder, true);
	}

	private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestCurrentChunkZ(
		CommandContext<CommandSource> context, SuggestionsBuilder builder) {
		return suggestCurrentChunkCoordinate(context.getSource(), builder, false);
	}

	private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestCurrentChunkCoordinate(
		CommandSource source, SuggestionsBuilder builder, boolean xCoordinate) {
		if (source instanceof ServerCommandSource serverSource && serverSource.player != null) {
			TilePosc blockPosition = serverSource.getBlockCoordinates();
			builder.suggest(xCoordinate ? blockPosition.x() >> 4 : blockPosition.z() >> 4,
				new LiteralMessage(xCoordinate ? "Your current chunk X coordinate" : "Your current chunk Z coordinate"));
		}
		return builder.buildFuture();
	}

	private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestWhitelistedChunkX(
		CommandContext<CommandSource> context, SuggestionsBuilder builder) {
		World world = context.getSource().getWorld();
		if (world instanceof WorldServer) {
			TreeSet<Integer> chunkXs = new TreeSet<>();
			for (ChunkPos chunkPos : ChunkWhitelistStorage.getWhitelistedChunkPositions(world)) {
				chunkXs.add(chunkPos.x());
			}
			for (int chunkX : chunkXs) {
				builder.suggest(chunkX, new LiteralMessage("Whitelisted chunk X coordinate"));
			}
		}
		return builder.buildFuture();
	}

	private static CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestWhitelistedChunkZ(
		CommandContext<CommandSource> context, SuggestionsBuilder builder) {
		World world = context.getSource().getWorld();
		if (world instanceof WorldServer) {
			int chunkX = context.getArgument("chunkX", Integer.class);
			for (ChunkPos chunkPos : ChunkWhitelistStorage.getWhitelistedChunkPositions(world)) {
				if (chunkPos.x() == chunkX) {
					builder.suggest(chunkPos.z(), new LiteralMessage("Whitelisted chunk Z coordinate"));
				}
			}
		}
		return builder.buildFuture();
	}

	private enum WhitelistAction {
		ADD("add"),
		REMOVE("remove");

		private final String commandName;

		WhitelistAction(String commandName) {
			this.commandName = commandName;
		}
	}
}
