package claude.settlementcore.work;

import claude.settlementcore.unit.SettlerMob;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

/**
 * One job for one of a room's crew (entity/CrewGoal): go to {@link #at}, get within {@link #reach},
 * work there for {@link #work} ticks (the dig animation plays), then {@link Done#done} does the work
 * itself, and may hand back the chore's next leg (a carried load's way to where it goes). A chore that
 * follows a beast keeps {@link #follow} and goes to wherever it is now.
 *
 * <p>Whatever a rat carries is in its hand (the item shows, and drops if it dies); {@link #abandon} is
 * called if it gives up half-way, so a load in its hand goes back where it came from.
 */
public final class Chore {

    @FunctionalInterface
    public interface Done {
        /** The work, done on arrival. Returns the next leg, or null when the chore is over. */
        @Nullable Chore done(ServerLevel level, SettlerMob rat);
    }

    /** One swing of the work, {@code fraction} of the way through it: a raised building's blocks go in bite by bite. */
    @FunctionalInterface
    public interface Swing {
        void swing(ServerLevel level, SettlerMob rat, float fraction);
    }

    /** What it squeaks if the Warlord asks (and what shows in test failures). */
    public final String what;
    public BlockPos at;
    public final double reach;
    public final int work;
    private final Done done;
    @Nullable public final Entity follow;
    /** Where a load in its hand goes back to if it gives up: a container, or null (it is dropped where the rat stands). */
    @Nullable public BlockPos returnTo;
    /** A key another rat's chore is kept off while this one runs (a furnace, a crop, a drift). */
    @Nullable public final Object claim;
    /**
     * The cell to walk to when the rat is out of reach of {@link #at}, where {@code CrewGoal#approach} could find none to stand in (a step
     * of the winze in the rock: its stair's top is no sturdy floor, so approach hands back the rock cell and vanilla lifts a path to it to
     * the first air above, a turn up the stair, where the rat stood for 400 ticks).
     */
    @Nullable public BlockPos standAt;
    /** Called on each swing of the work (CrewGoal), before {@link Done#done} at its end. */
    @Nullable public Swing swing;

    public Chore(String what, BlockPos at, double reach, int work, @Nullable Object claim, Done done) {
        this(what, at, reach, work, claim, null, done);
    }

    public Chore(String what, BlockPos at, double reach, int work, @Nullable Object claim, @Nullable Entity follow, Done done) {
        this.what = what;
        this.at = at;
        this.reach = reach;
        this.work = work;
        this.claim = claim;
        this.follow = follow;
        this.done = done;
    }

    public Chore returning(@Nullable BlockPos to) {
        this.returnTo = to;
        return this;
    }

    public Chore standing(@Nullable BlockPos cell) {
        this.standAt = cell;
        return this;
    }

    public Chore swinging(@Nullable Swing s) {
        this.swing = s;
        return this;
    }

    @Nullable
    public Chore finish(ServerLevel level, SettlerMob rat) {
        return done.done(level, rat);
    }

    /** It gave up (a fight, a rout, lost its way): a load in its hand goes back, or onto the floor. */
    public void abandon(ServerLevel level, SettlerMob rat) {
        var held = rat.carried();
        if (held.isEmpty()) {
            return;
        }
        var rest = held;
        if (returnTo != null) {
            var c = Bins.at(level, returnTo);
            if (c != null) {
                rest = Bins.insert(c, held);
            }
        }
        if (!rest.isEmpty()) {
            rat.spawnAtLocation((net.minecraft.server.level.ServerLevel) rat.level(), rest);
        }
        rat.carry(net.minecraft.world.item.ItemStack.EMPTY);
    }
}
