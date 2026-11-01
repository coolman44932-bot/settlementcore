package claude.settlementcore.testrace;

import claude.settlementcore.settlement.Currencies;
import claude.settlementcore.settlement.Settlements;
import claude.settlementcore.test.GameTests;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * The test race (2026-10-31): the smallest whole race on Settlement Core, dev-only, in the engine's own repo. It shows the engine
 * running in a real game, and it is the template the next race copies (a revived one, or the Chaos Dwarfs):
 * <ul>
 * <li>a {@link HeartBlock}, whose block entity ({@link HeartBlockEntity}) is the engine's settlement with the race's few hooks
 * answered; placed, it is its placer's;</li>
 * <li>two units on one base ({@link TestUnit}): the {@link Worker}, who crews and digs, and the {@link Guard}, who fights; the
 * Heart makes them from food (a round of spawning every 30 s) and brings them up while it has room;</li>
 * <li>emeralds its currency, mined from emerald ore;</li>
 * <li>a crowd drawn by the engine's renderer (client/), and its own GameTests (test/).</li>
 * </ul>
 * Nothing here is shipped to a player: the build leaves it out of the engine's jar.
 */
@Mod(TestRace.MODID)
public final class TestRace {
    public static final String MODID = "testrace";
    public static final Logger LOG = LogUtils.getLogger();

    public TestRace(IEventBus modBus, ModContainer container) {
        TRRegistry.register(modBus);
        Currencies.register(() -> Items.EMERALD, () -> Items.EMERALD_BLOCK, null, () -> Blocks.EMERALD_ORE, () -> Blocks.DEEPSLATE_EMERALD_ORE);
        Settlements.ownedBy(HeartBlockEntity::ownedBy);
        GameTests.register(container, modBus);
    }
}
