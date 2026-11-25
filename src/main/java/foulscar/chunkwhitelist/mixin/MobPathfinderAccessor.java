package foulscar.chunkwhitelist.mixin;

import net.minecraft.core.entity.MobPathfinder;
import net.minecraft.core.world.pathfinder.Path;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MobPathfinder.class)
public interface MobPathfinderAccessor {
	@Accessor("pathToEntity")
	void chunkwhitelist$setPathToEntity(Path path);
}
