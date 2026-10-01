package claude.underempire.core.settlement;

import net.minecraft.server.level.ServerLevel;

/**
 * How a settlement makes new people (Under-Empire: the pens whelp and the Throne Hall raises clanrats; a Spawning Pool hatches
 * Skinks): one {@link #round} of it, which the settlement runs every so many seconds of its rooms' clock
 * ({@link SettlementBlockEntity#spawnSeconds}). A round does what it likes through the {@link Brood} it is handed, and the
 * engine keeps the bounds: nothing is made past the room the settlement has, or without the food it costs, and no more come up
 * as mobs than it may have up at once. What a round makes, how many, and what it costs is the race's.
 */
@FunctionalInterface
public interface Spawning {

    /** A settlement that makes no one. */
    Spawning NONE = (level, settlement, brood) -> {
    };

    /** One round: {@code settlement} makes what it makes, by way of {@code brood}. */
    void round(ServerLevel level, Settlement settlement, Brood brood);
}
