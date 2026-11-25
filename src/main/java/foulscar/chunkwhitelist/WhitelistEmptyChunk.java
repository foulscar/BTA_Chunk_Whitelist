package foulscar.chunkwhitelist;

import net.minecraft.core.world.World;
import net.minecraft.core.world.chunk.Chunk;
import net.minecraft.core.world.pos.ChunkPosc;

public final class WhitelistEmptyChunk extends Chunk {
	public WhitelistEmptyChunk(World world, ChunkPosc chunkPos) {
		super(world, chunkPos);
	}
}
