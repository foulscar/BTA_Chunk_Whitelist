package foulscar.chunkwhitelist;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.ArgumentTypeInteger;
import com.mojang.brigadier.builder.ArgumentBuilderLiteral;
import com.mojang.brigadier.builder.ArgumentBuilderRequired;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import foulscar.chunkwhitelist.mixin.PlayerInstanceAccessor;
import foulscar.chunkwhitelist.mixin.PlayerManagerAccessor;
import net.minecraft.core.net.command.CommandManager;
import net.minecraft.core.net.command.CommandSource;
import net.minecraft.core.world.World;
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
import java.util.List;
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

	private static int showUsage(CommandContext<CommandSource> context) {
		context.getSource().sendMessage("Usage: /chunk tp <chunkX> <chunkZ>");
		context.getSource().sendMessage("Usage: /chunk whitelist add [chunkX] [chunkZ]");
		context.getSource().sendMessage("       /chunk whitelist remove [chunkX] [chunkZ]");
		context.getSource().sendMessage("       /chunk whitelist list");
		context.getSource().sendMessage("Without coordinates, the command uses your current chunk. Coordinate commands also work from the console.");
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

	private static long getChunkWatcherKey(int chunkX, int chunkZ) {
		return (((long) chunkZ + Integer.MAX_VALUE) << 32) | ((long) chunkX + Integer.MAX_VALUE);
	}

	private static int changeCurrentChunk(CommandContext<CommandSource> context, WhitelistAction action) throws CommandSyntaxException {
		ServerCommandSource source = (ServerCommandSource) context.getSource();
		if (source.player == null) {
			source.sendMessage("From the console, specify chunk coordinates: /chunk whitelist "
				+ action.commandName + " <chunkX> <chunkZ>");
			return 0;
		}

		TilePosc blockPosition = source.getBlockCoordinates();
		return changeChunkWhitelist(source, blockPosition.x() >> 4, blockPosition.z() >> 4, action);
	}

	private static int changeChunkAtCoordinates(CommandContext<CommandSource> context, WhitelistAction action) throws CommandSyntaxException {
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
