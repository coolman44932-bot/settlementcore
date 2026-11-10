package claude.settlementcore.client;

import claude.settlementcore.ledger.LedgerMenu;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The looks a Ledger can open in, by id: the engine's own under {@link #DEFAULT_ID}, and each race's under an id of its own
 * (its mod id, by custom). The server names only the id (Settlement#ledgerLook, sent as the screen opens); the look itself is
 * the client's. An id no one registered opens in the engine's look, so a race's server with an older client still shows a
 * Ledger.
 */
public final class LedgerLooks {

    /** The engine's look's id: its mod id. */
    public static final String DEFAULT_ID = LedgerMenu.DEFAULT_LOOK;

    /** Mods are constructed side by side, and each may register its look from its constructor. */
    private static final Map<String, LedgerLook> LOOKS = new ConcurrentHashMap<>();

    static {
        LOOKS.put(DEFAULT_ID, LedgerLook.ENGINE);
    }

    private LedgerLooks() {
    }

    /** {@code look} under {@code id}; the same id again replaces it (the engine's own too, should a pack want another). */
    public static void register(String id, LedgerLook look) {
        LOOKS.put(id, look);
    }

    /** The look registered under {@code id}, or the engine's for an id no one registered. */
    public static LedgerLook get(String id) {
        LedgerLook look = id == null ? null : LOOKS.get(id);
        return look != null ? look : LOOKS.get(DEFAULT_ID);
    }
}
