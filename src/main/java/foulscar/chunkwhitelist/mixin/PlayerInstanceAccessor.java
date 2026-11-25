package foulscar.chunkwhitelist.mixin;

import net.minecraft.server.entity.player.PlayerServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(targets = "net.minecraft.server.player.PlayerManager$PlayerInstance")
public interface PlayerInstanceAccessor {
	@Accessor("players")
	List<PlayerServer> chunkWhitelist$getPlayers();
}
