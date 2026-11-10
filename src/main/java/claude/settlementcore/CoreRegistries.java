package claude.settlementcore;

import claude.settlementcore.ledger.LedgerMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The little the engine registers for its own workings: no block, item or entity (those are a race's).
 * Since 1.21.5 a chunk ticket's type is a registry entry, so the ledger's ticket is one; and the Ledger's screen is the engine's,
 * one for every race, so its menu type is too (a race opens it: ledger/LedgerMenu#open).
 */
public final class CoreRegistries {

    private static final DeferredRegister<TicketType> TICKETS = DeferredRegister.create(Registries.TICKET_TYPE, Core.MODID);

    /** The ledger's look into a store's chunk: loads it to FULL (its block entities), never ticks it, gone after 30 s. */
    public static final DeferredHolder<TicketType, TicketType> LEDGER_TICKET = TICKETS.register("ledger", () -> new TicketType(20 * 30, TicketType.FLAG_LOADING));

    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Core.MODID);

    /** The Ledger: its client side is made of what the server writes as it opens (the heart's place, the look, the settlement's name). */
    public static final DeferredHolder<MenuType<?>, MenuType<LedgerMenu>> LEDGER_MENU = MENUS.register("ledger", () -> IMenuTypeExtension.create(LedgerMenu::new));

    private CoreRegistries() {
    }

    static void register(IEventBus modBus) {
        TICKETS.register(modBus);
        MENUS.register(modBus);
    }
}
