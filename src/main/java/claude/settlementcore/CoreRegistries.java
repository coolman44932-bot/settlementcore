package claude.settlementcore;

import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.TicketType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The little the engine registers for its own workings: nothing a player sees (no block, item or entity; those are a race's).
 * Since 1.21.5 a chunk ticket's type is a registry entry, so the ledger's ticket is one.
 */
public final class CoreRegistries {

    private static final DeferredRegister<TicketType> TICKETS = DeferredRegister.create(Registries.TICKET_TYPE, Core.MODID);

    /** The ledger's look into a store's chunk: loads it to FULL (its block entities), never ticks it, gone after 30 s. */
    public static final DeferredHolder<TicketType, TicketType> LEDGER_TICKET = TICKETS.register("ledger", () -> new TicketType(20 * 30, TicketType.FLAG_LOADING));

    private CoreRegistries() {
    }

    static void register(IEventBus modBus) {
        TICKETS.register(modBus);
    }
}
