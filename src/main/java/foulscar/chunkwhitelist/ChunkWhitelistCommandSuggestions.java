package foulscar.chunkwhitelist;

import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.core.net.command.CommandSource;
import net.minecraft.core.world.World;
import net.minecraft.server.net.command.ServerCommandSource;
import net.minecraft.server.world.WorldServer;

import java.util.concurrent.CompletableFuture;

final class ChunkWhitelistCommandSuggestions {
	private ChunkWhitelistCommandSuggestions() {
	}

	static CompletableFuture<Suggestions> suggestDimensionIds(
		CommandContext<CommandSource> context, SuggestionsBuilder builder) {
		if (context.getSource() instanceof ServerCommandSource source) {
			source.server.dimensionWorlds.int2ObjectEntrySet().forEach(entry ->
				builder.suggest(entry.getIntKey(),
					new LiteralMessage(entry.getValue().dimension.getTranslatedName())));
		}
		return builder.buildFuture();
	}

	static CompletableFuture<Suggestions> suggestWhitelistedChunkX(
		CommandContext<CommandSource> context, SuggestionsBuilder builder) {
		World world = context.getSource().getWorld();
		if (world instanceof WorldServer) {
			for (int chunkX : ChunkWhitelistStorage.getWhitelistedChunkXCoordinates(world)) {
				builder.suggest(chunkX, new LiteralMessage("Whitelisted chunk X coordinate"));
			}
		}
		return builder.buildFuture();
	}

	static CompletableFuture<Suggestions> suggestWhitelistedChunkZ(
		CommandContext<CommandSource> context, SuggestionsBuilder builder) {
		World world = context.getSource().getWorld();
		if (world instanceof WorldServer) {
			int chunkX = context.getArgument("chunkX", Integer.class);
			for (int chunkZ : ChunkWhitelistStorage.getWhitelistedChunkZCoordinates(world, chunkX)) {
				builder.suggest(chunkZ, new LiteralMessage("Whitelisted chunk Z coordinate"));
			}
		}
		return builder.buildFuture();
	}

	static CompletableFuture<Suggestions> suggestCurrentChunkX(
		CommandContext<CommandSource> context, SuggestionsBuilder builder) {
		if (context.getSource() instanceof ServerCommandSource source && source.player != null) {
			int chunkX = (int) Math.floor(source.player.x / 16.0);
			builder.suggest(chunkX, new LiteralMessage("Current chunk X coordinate"));
		}
		return builder.buildFuture();
	}

	static CompletableFuture<Suggestions> suggestCurrentChunkZ(
		CommandContext<CommandSource> context, SuggestionsBuilder builder) {
		if (context.getSource() instanceof ServerCommandSource source && source.player != null) {
			int chunkZ = (int) Math.floor(source.player.z / 16.0);
			builder.suggest(chunkZ, new LiteralMessage("Current chunk Z coordinate"));
		}
		return builder.buildFuture();
	}
}
