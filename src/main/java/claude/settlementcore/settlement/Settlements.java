package claude.settlementcore.settlement;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Which settlement a player owns, as each race keeps track of it (Under-Empire: the clan roster's Heart, clan/Roster#heart).
 * Races register a finder as they start; the engine asks them in turn (core/work/Den: whose den a sleeper is in).
 */
public final class Settlements {

    private static final List<Function<ServerPlayer, ? extends Settlement>> FINDERS = new ArrayList<>();

    private Settlements() {
    }

    public static synchronized void ownedBy(Function<ServerPlayer, ? extends Settlement> finder) {
        FINDERS.add(finder);
    }

    /** The settlement {@code p} owns (loaded), or null. */
    @Nullable
    public static Settlement of(ServerPlayer p) {
        for (Function<ServerPlayer, ? extends Settlement> f : FINDERS) {
            Settlement s = f.apply(p);
            if (s != null) {
                return s;
            }
        }
        return null;
    }
}
