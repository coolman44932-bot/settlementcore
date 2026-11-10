package claude.settlementcore.client;

import claude.settlementcore.Core;
import claude.settlementcore.CoreRegistries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;

/**
 * The Ledger's menu gets its screen ({@link LedgerScreen}), on a client only: a server never loads this class, as it is
 * subscribed for {@link Dist#CLIENT} alone, and nothing else in the engine names the screen.
 */
@EventBusSubscriber(modid = Core.MODID, value = Dist.CLIENT)
public final class LedgerClient {

    private LedgerClient() {
    }

    @SubscribeEvent
    static void screens(RegisterMenuScreensEvent e) {
        e.register(CoreRegistries.LEDGER_MENU.get(), LedgerScreen::new);
    }

    /** The open Ledger turned to a page, from any thread (dev/BridgeHooks: a tool that photographs screens). */
    public static void turnTo(int page) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.screen instanceof LedgerScreen s) {
                s.turnToPage(page);
            }
        });
    }
}
