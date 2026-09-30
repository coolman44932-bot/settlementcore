package claude.underempire.core;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Settlement Core as a mod: it registers its own settings (config/settlementcore-common.toml and -client.toml), which a
 * race that kept them before moves the player's values into first ({@link CoreConfig#adoptLegacy}). Everything else the
 * engine does, a race asks of it.
 */
@Mod(Core.MODID)
public final class CoreMod {

    public CoreMod(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, CoreConfig.SPEC, CoreConfig.COMMON_FILE);
        container.registerConfig(ModConfig.Type.CLIENT, CoreConfig.CLIENT_SPEC, CoreConfig.CLIENT_FILE);
    }
}
