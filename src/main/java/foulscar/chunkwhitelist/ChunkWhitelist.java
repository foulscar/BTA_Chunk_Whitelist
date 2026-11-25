package foulscar.chunkwhitelist;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import turniplabs.halplibe.HalpLibe;

public final class ChunkWhitelist implements ModInitializer {
	public static final String MOD_ID = HalpLibe.registerMod("chunkwhitelist", true);
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ChunkWhitelistCommands.registerCommands();
		LOGGER.info("Chunk Whitelist initialized.");
	}
}
