package claude.settlementcore;

import claude.settlementcore.dev.BridgeHooks;
import claude.settlementcore.ledger.LedgerNet;
import claude.settlementcore.test.GameTests;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;

/**
 * Settlement Core as a mod: it registers its own settings (config/settlementcore-common.toml and -client.toml), which a
 * race that kept them before moves the player's values into first ({@link CoreConfig#adoptLegacy}), and, where GameTests
 * are on, its own tests ({@link GameTests}); in a dev game with devbridge, what it tells that bridge ({@link BridgeHooks}).
 * It registers the one screen that is every race's, the Ledger: its menu type ({@link CoreRegistries}) and its payloads
 * ({@link LedgerNet}); the screen itself is the client's to register (client/LedgerClient). Everything else the engine does, a
 * race asks of it.
 */
@Mod(Core.MODID)
public final class CoreMod {

    public CoreMod(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, CoreConfig.SPEC, CoreConfig.COMMON_FILE);
        container.registerConfig(ModConfig.Type.CLIENT, CoreConfig.CLIENT_SPEC, CoreConfig.CLIENT_FILE);
        CoreRegistries.register(modBus);
        LedgerNet.register(modBus);
        GameTests.registerEngine(modBus);
        GameTests.register(container, modBus);
        modBus.addListener(InterModEnqueueEvent.class, BridgeHooks::enqueue);
    }
}
