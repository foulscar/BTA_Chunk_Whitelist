package foulscar.chunkwhitelist.mixin;

import net.minecraft.server.net.PlayerHash;
import net.minecraft.server.player.PlayerManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PlayerManager.class)
public interface PlayerManagerAccessor {
	@Accessor("viewRadius")
	int chunkWhitelist$getViewRadius();

	@Accessor("playerInstances")
	PlayerHash chunkWhitelist$getPlayerInstances();
}
