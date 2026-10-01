package claude.underempire.core.settlement;

import net.minecraft.server.level.ServerLevel;

/**
 * What a race does when its settlement has no food left (Under-Empire: after five minutes of it the horde devours its weakest
 * and a rat of the warband goes rogue; a Spawning Pool's people sicken). The engine counts the seconds ({@link Ration#second});
 * the race says what they cost. A settlement names its policy with {@link Settlement#famine()}, which is none by default.
 */
@FunctionalInterface
public interface Famine {

    /** Hunger with no consequence: what a settlement that names no policy has. */
    Famine NONE = (level, settlement, seconds) -> {
    };

    /**
     * Called every second the settlement has no food and still has people, with the {@code seconds} it has gone hungry so far:
     * 1 the first time, then 2, 3 and so on. A policy that warns now and again, and bites once a long hunger has gone on, reads
     * it from {@code seconds}.
     */
    void starving(ServerLevel level, Settlement settlement, int seconds);

    /** Called once when food is back after a hunger. */
    default void fed(ServerLevel level, Settlement settlement) {
    }
}
