package foulscar.chunkwhitelist.mixin;

import net.minecraft.core.world.chunk.Chunk;
import net.minecraft.server.world.chunk.provider.ChunkProviderServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@Mixin(ChunkProviderServer.class)
public interface ChunkProviderServerAccessor {
	@Accessor("chunkList")
	List<Chunk> chunkwhitelist$getLoadedChunks();

	@Invoker("saveChunkToFile")
	void chunkwhitelist$saveChunkToFile(Chunk chunk);
}
