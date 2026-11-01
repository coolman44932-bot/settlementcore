package claude.settlementcore.settlement;

import claude.settlementcore.unit.Footing;
import claude.settlementcore.unit.SettlerMob;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * What one round of a settlement's making people ({@link Spawning}) may do, inside the engine's bounds. The Warren Heart's
 * {@code breed()} checked these by hand at each pup and each clanrat (food over a floor, population under the clan's cap,
 * the warband under its own), and lifted here a Spawning Pool's rounds check the same things:
 * <ul>
 * <li>{@link #add}: more of the reserve, refused past {@link #room()} or without the food, and the food spent through the
 *     settlement's ration;</li>
 * <li>{@link #bringUp}: one out of the reserve as a mob, refused at the settlement's {@code peopleCap}, and never into a hollow shut in
 *     on every side ({@link Footing#escapes});</li>
 * <li>{@link #rate} and {@link #scaled}: how fast it breeds, the fare's breeding times the settlement's own multiplier (a pool's
 *     warm water, a Heart's devotion), and a number scaled by it with the fraction rounded up or down at random.</li>
 * </ul>
 * It holds nothing between rounds: the settlement makes a new one for each (docs/plans/greatplan-first-spawning.md, section B
 * item 3).
 */
public final class Brood {

    /** Food out of the settlement's ration: {@link Ration#spendFood(int, String)}, or a settlement's own. Returns how much there was to spend. */
    @FunctionalInterface
    public interface Spend {
        int spend(int n, String on);
    }

    /**
     * What making one person costs: {@code each} food points, none made while the settlement holds under {@code atLeast} (the
     * pens stay quiet below 10 food, though a pup costs 1), logged on the road to {@code on} ("Breeding the horde").
     */
    public record Cost(int each, int atLeast, String on) {

        /** The food the settlement must hold for one to be made. */
        public int needed() {
            return Math.max(each, atLeast);
        }
    }

    private final ServerLevel level;
    private final Settlement settlement;
    private final int cap;
    private final float multiplier;
    private final Spend spend;

    /**
     * @param cap         the most people the settlement can number in all (its people, its crews and its reserve together)
     * @param multiplier  the settlement's own breeding rate, 1 for none: a Heart's devotion, a pool's warm water
     * @param spend       where the food a person costs goes out of
     */
    public Brood(ServerLevel level, Settlement settlement, int cap, float multiplier, Spend spend) {
        this.level = level;
        this.settlement = settlement;
        this.cap = cap;
        this.multiplier = multiplier;
        this.spend = spend;
    }

    /** How many more the settlement has room for: its cap less everyone it counts ({@link Settlement#population}), never under none. */
    public int room() {
        return Math.max(0, cap - settlement.population());
    }

    /** How fast it breeds: the fare's breeding ({@code fare().breeding}) times the settlement's own multiplier. */
    public float rate() {
        return settlement.fare().breeding * multiplier;
    }

    /** {@code n} times {@link #rate}, the fraction rounded up or down at random (a litter half a pup bigger is one in two). */
    public int scaled(int n) {
        float f = n * rate();
        int whole = (int) Math.floor(f);
        return whole + (level.random.nextFloat() < f - whole ? 1 : 0);
    }

    /**
     * Up to {@code n} more of the reserve, workers or {@code soldier}s, one at a time, each only while the settlement has room for
     * it and holds the food {@code cost} asks (the food goes out as each is made). Returns how many were made.
     */
    public int add(boolean soldier, int n, Cost cost) {
        int made = 0;
        for (int i = 0; i < n && room() > 0 && settlement.food() >= cost.needed(); i++) {
            settlement.addToReserve(soldier ? 0 : 1, soldier ? 1 : 0);
            spend.spend(cost.each(), cost.on());
            made++;
        }
        return made;
    }

    /**
     * One out of the reserve up as a mob at {@code at}, if the settlement has fewer up than its {@code peopleCap}: null if it is
     * at its cap, the reserve holds none of the kind, the race could not make one (the one taken goes back), or {@code at} is a cell a
     * settler can stand in but a hollow shut in on every side ({@link Footing#escapes}) and there is no cell near it that is not
     * ({@link Settlement#findStandable}): none is taken from the reserve then. A cell with a way out, or one no settler stands in, is
     * where the race asked.
     */
    @Nullable
    public SettlerMob bringUp(boolean soldier, BlockPos at) {
        if (settlement.peopleCount() >= settlement.peopleCap()) {
            return null;
        }
        BlockPos up = at;
        if (level.isLoaded(at) && SettlerMob.standable(level, at) && !Footing.escapes(level, at, Footing.LIMIT)) {
            up = Settlement.findStandable(level, at);
            if (!Footing.escapes(level, up, Footing.LIMIT)) {
                return null;
            }
        }
        return settlement.riseFromReserve(level, up, soldier, false);
    }
}
