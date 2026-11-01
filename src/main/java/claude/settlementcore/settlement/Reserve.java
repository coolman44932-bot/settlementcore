package claude.settlementcore.settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * A settlement's reserve: the people who are not up and about but wait, kept as two counts, workers and soldiers (the Warren
 * Heart's horde of slaves and clanrats; a Spawning Pool's skinks and saurus). One rises out of it as a mob when the settlement
 * needs one ({@link #rise}), and goes back into it by {@link #add}. Lifted from the Heart (docs/plans/
 * greatplan-first-spawning.md, section B item 2): the counts are saved under keys the race names, so the Heart's
 * {@code horde_slaves} and {@code horde_clanrats} read back through here.
 *
 * <p>The reserve changes nothing else: its owner calls {@code setChanged} after a change, as the Heart does.
 */
public final class Reserve {

    /** The race's "make the mob": a settler of the kind asked for, placed in the world and bound to its settlement, or null if it cannot be made. */
    @FunctionalInterface
    public interface Raiser<T extends Entity> {
        @Nullable
        T raise(ServerLevel level, BlockPos at, boolean soldier);
    }

    private final String workersKey;
    private final String soldiersKey;
    private int workers;
    private int soldiers;

    /** @param workersKey   the save key of the workers' count (the Heart's "horde_slaves")
     *  @param soldiersKey  the save key of the soldiers' count (the Heart's "horde_clanrats") */
    public Reserve(String workersKey, String soldiersKey) {
        this.workersKey = workersKey;
        this.soldiersKey = soldiersKey;
    }

    public int workers() {
        return workers;
    }

    public int soldiers() {
        return soldiers;
    }

    /** Workers and soldiers together. */
    public int count() {
        return workers + soldiers;
    }

    /** More (or, negative, fewer) of each; neither count goes under none. */
    public void add(int workers, int soldiers) {
        this.workers = Math.max(0, this.workers + workers);
        this.soldiers = Math.max(0, this.soldiers + soldiers);
    }

    /** One of the kind out of the reserve, if there is one. */
    public boolean take(boolean soldier) {
        if (soldier ? soldiers <= 0 : workers <= 0) {
            return false;
        }
        if (soldier) {
            soldiers--;
        } else {
            workers--;
        }
        return true;
    }

    /**
     * One of the kind rises out of the reserve at {@code at}, made by {@code raiser}. Null if the reserve holds none of that kind
     * (the raiser is not asked), or the raiser could not make one: then the one taken goes back.
     */
    @Nullable
    public <T extends Entity> T rise(ServerLevel level, BlockPos at, boolean soldier, Raiser<T> raiser) {
        if (!take(soldier)) {
            return null;
        }
        T made = raiser.raise(level, at, soldier);
        if (made == null) {
            add(soldier ? 0 : 1, soldier ? 1 : 0);
        }
        return made;
    }

    /** Both counts, under the keys the race named. */
    public void save(CompoundTag tag) {
        tag.putInt(workersKey, workers);
        tag.putInt(soldiersKey, soldiers);
    }

    /**
     * Both counts from the tag, if it has the workers' key: returns true. A tag without it (a settlement from before the reserve
     * was counted) changes nothing, and returns false, for the race to say what such a settlement holds.
     */
    public boolean load(CompoundTag tag) {
        if (!tag.contains(workersKey)) {
            return false;
        }
        workers = tag.getIntOr(workersKey, 0);
        soldiers = tag.getIntOr(soldiersKey, 0);
        return true;
    }
}
