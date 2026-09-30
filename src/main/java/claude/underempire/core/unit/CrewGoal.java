package claude.underempire.core.unit;

import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.work.Chore;
import claude.underempire.core.work.RoomWork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

/**
 * A work room's crew at its chores (nest/work): ask the room for the next one, go there, work at it
 * (the dig animation) for as long as it takes, let it do its work, and follow on to its next leg
 * (a load in the hand carried on). A fight, a rout or losing its way ends the chore, and whatever it
 * was carrying goes back. Out of chores it mills about its own room ({@link Idle}).
 */
public class CrewGoal extends Goal {

    private static final int GIVE_UP = 400;

    private final SettlerMob rat;
    @Nullable private Chore chore;
    @Nullable private RoomWork room;
    private int stuck;
    private int worked;
    private int nextTry;
    private int nextPath;
    private int nextSwing;

    public CrewGoal(SettlerMob rat) {
        this.rat = rat;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private RoomWork room() {
        Settlement h = rat.settlement();
        return h == null ? null : h.works().roomOf(rat);
    }

    @Override
    public boolean canUse() {
        if (!rat.isCrew() || rat.isBroken() || rat.getTarget() != null || rat.isBurrowing() || rat.isHomeward()
                || rat.tickCount < nextTry || !(rat.level() instanceof ServerLevel sl)) {
            return false;
        }
        nextTry = rat.tickCount + 10;                            // a cooldown, never tickCount % n (see DigGoal.nextClaim)
        room = room();
        if (room == null) {
            return false;
        }
        chore = room.nextChore(sl, rat);
        if (chore == null) {
            nextTry = rat.tickCount + 30 + rat.getRandom().nextInt(30);
            return false;
        }
        room.claim(chore.claim, sl.getGameTime());
        trace("pick");
        return true;
    }

    /** gradlew runGameTestServer -Pcrewtrace: every crew rat's chores to the log (a stall in a room's work shows who stood where). */
    private static final boolean TRACE = Boolean.getBoolean("underempire.crewtrace");

    private void trace(String event) {
        if (!TRACE || chore == null || !(rat.level() instanceof ServerLevel sl)) {
            return;
        }
        var h = rat.settlement();
        com.mojang.logging.LogUtils.getLogger().info("crewtrace heart={} t={} rat={} {} '{}' at={} follow={} ratAt={} ground={} stuck={} "
                        + "worked={} navDone={} target={} broken={} goals={}",
                h == null ? "?" : h.getBlockPos().toShortString(), sl.getGameTime(), rat.getId(), event, chore.what, chore.at.toShortString(),
                chore.follow == null ? "-" : chore.follow.getType().toShortString() + "#" + chore.follow.getId(),
                String.format("%.1f,%.1f,%.1f", rat.getX(), rat.getY(), rat.getZ()), rat.onGround(), stuck, worked,
                rat.getNavigation().isDone(), rat.getTarget(), rat.isBroken(), rat.runningGoals());
    }

    @Override
    public void start() {
        stuck = 0;
        worked = 0;
        nextPath = 0;
        nextSwing = 0;
        rat.crewDoing = chore == null ? "" : chore.what;
        rat.getNavigation().stop();                              // a walk another goal began (Idle's, home to its room) is not the chore's:
                                                                 // tick() makes the chore's path only once the navigation is done, so the rat
                                                                 // finished that walk first (the winze's crew climbed the whole stair so)
    }

    @Override
    public boolean canContinueToUse() {
        return chore != null && rat.isCrew() && !rat.isBroken() && rat.getTarget() == null && !rat.isBurrowing();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        if (chore == null || room == null || !(rat.level() instanceof ServerLevel sl)) {
            return;
        }
        if (chore.follow != null) {
            if (!chore.follow.isAlive()) {
                trace("gone");
                done(sl, null);
                return;
            }
            chore.at = chore.follow.blockPosition();
        }
        BlockPos at = chore.at;
        double dx = rat.getX() - (at.getX() + 0.5);
        double dz = rat.getZ() - (at.getZ() + 0.5);
        int dy = at.getY() - rat.getBlockY();
        boolean inReach = dx * dx + dz * dz <= chore.reach * chore.reach && dy <= 3 && dy >= -3;
        if (!inReach) {
            rat.setDigging(false);
            if (rat.getNavigation().isDone() && rat.tickCount >= nextPath) {
                nextPath = rat.tickCount + 20;
                BlockPos to = approach(at);
                var path = rat.getNavigation().createPath(to, 0);    // to the cell itself: a block short is out of reach
                if (path != null) {
                    rat.getNavigation().moveTo(path, 1.0);
                }
            }
            if (++stuck > GIVE_UP) {
                trace("unreachable");
                giveUp(sl, true);                                // lost its way: give it up (set aside a while), and the load back
            }
            return;
        }
        rat.getNavigation().stop();
        rat.getLookControl().setLookAt(at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5);
        if (chore.work > 2) {
            rat.setDigging(true);
            if (worked >= nextSwing) {
                nextSwing = worked + Math.max(4, Math.round((5 + rat.getRandom().nextInt(4)) / rat.tempo()));
                var state = sl.getBlockState(at);
                if (!state.isAir() && rat.getRandom().nextInt(3) == 0) {
                    sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), at.getX() + 0.5, at.getY() + 0.6,
                            at.getZ() + 0.5, 3, 0.25, 0.2, 0.25, 0.03);
                    sl.playSound(null, at, state.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.35f, 0.9f + rat.getRandom().nextFloat() * 0.2f);
                }
                if (chore.swing != null) {
                    chore.swing.swing(sl, rat, Math.min(1f, (worked + 1) / (float) chore.work));   // the work lands bite by bite
                }
            }
        }
        if (++worked >= chore.work) {
            rat.setDigging(false);
            trace("done");
            done(sl, chore.finish(sl, rat));
        }
    }

    /**
     * Where to stand for a chore at {@code at}. A chore in the open (a crop, a beast, the floor) is walked to;
     * one in solid stuff (a furnace, a barrel, a drift's rock face) from an open cell beside it, because
     * vanilla's ground navigation lifts a target inside a solid block to the first air ABOVE it: sent at a
     * drift face, a rat climbed onto the barrels toward the sky over the rock.
     */
    private BlockPos approach(BlockPos at) {
        if (chore != null && chore.standAt != null) {
            return chore.standAt;                                // the room knows where the work is done from (a step of the winze's stair)
        }
        var level = rat.level();
        if (level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
            return at;
        }
        double reach = (chore == null ? 2.2 : chore.reach) - 0.35;
        int r = (int) Math.ceil(reach);
        BlockPos from = rat.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > reach * reach) {
                    continue;                                    // only cells it can work from
                }
                for (int dy = 1; dy >= -3; dy--) {
                    BlockPos p = at.offset(dx, dy, dz);
                    if (SettlerMob.standable(level, p)) {
                        // about a block from the work (the Warlock's bench clip taps 0.9 ahead), then the nearest to walk to
                        double off = Math.sqrt(dx * dx + dz * dz) - 1.1;
                        double d = off * off * 40 + p.distSqr(from) + (SettlerMob.underOpenSky(level, p) ? 10_000 : 0);
                        if (d < bestD) {
                            bestD = d;
                            best = p;
                        }
                        break;                                   // the highest open floor in this column
                    }
                }
            }
        }
        return best != null ? best : at;
    }

    private void done(ServerLevel sl, @Nullable Chore next) {
        Chore was = chore;
        if (room != null && was != null && (next == null || next.claim != was.claim)) {
            room.release(was.claim);
        }
        if (next != null) {
            chore = next;
            room.claim(next.claim, sl.getGameTime());
            rat.crewDoing = next.what;
            worked = 0;
            stuck = 0;
            nextPath = 0;
            nextSwing = 0;
        } else {
            if (!rat.carried().isEmpty() && was != null) {
                was.abandon(sl, rat);                            // a leg that ended with something still in its hand
            }
            chore = null;
            nextTry = rat.tickCount + 2 + rat.getRandom().nextInt(8);
        }
    }

    private void giveUp(ServerLevel sl) {
        giveUp(sl, false);
    }

    /** Drop the chore; {@code unreachable}: it could not be got to, so it is set aside a while (RoomWork#shelve). */
    private void giveUp(ServerLevel sl, boolean unreachable) {
        if (chore != null) {
            chore.abandon(sl, rat);
            if (room != null) {
                if (unreachable) {
                    room.shelve(chore.claim, sl.getGameTime());
                } else {
                    room.release(chore.claim);
                }
            }
        }
        chore = null;
        nextTry = rat.tickCount + 60;
        if (unreachable) {
            gnawOutIfPenned(sl);
        }
    }

    /**
     * A chore out of reach may be the rat's own fault: penned in somewhere it cannot walk out of (dropped into a
     * paddock, down a hole). If it cannot walk back to where its room's crew comes up, it gnaws its way out, down
     * into the earth and back up there, as a crew rat comes up out of the tunnels. Before this a penned rat
     * picked chore after chore it could never reach, for good (2026-09-26: the Beast Pens' crew, shut in with the
     * cows, warp-feeding pigs and chickens beyond the fences forever).
     */
    private void gnawOutIfPenned(ServerLevel sl) {
        if (room == null || rat.isBurrowing()) {
            return;
        }
        BlockPos up = room.comeUpAt(sl);
        if (up == null || up.equals(rat.blockPosition())) {
            return;
        }
        rat.getNavigation().stop();
        var path = rat.getNavigation().createPath(up, 1);
        if (path != null && path.canReach()) {
            return;                                              // it can walk there: only the chore was out of reach
        }
        rat.burrowTo(up.getX() + 0.5, up.getY(), up.getZ() + 0.5);
        rat.gnawedOut++;
    }

    @Override
    public void stop() {
        rat.setDigging(false);
        if (chore != null && rat.level() instanceof ServerLevel sl) {
            trace("stopped");
            giveUp(sl);
        }
        chore = null;
        rat.crewDoing = "";
    }

    /** Nothing to do: it mills about its own room, now and then worrying at a wall like any slave. */
    public static class Idle extends Goal {
        private final SettlerMob rat;
        private int next;
        private int busy;
        @Nullable private BlockPos wall;

        public Idle(SettlerMob rat) {
            this.rat = rat;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Nullable
        private RoomWork room() {
            Settlement h = rat.settlement();
            return h == null ? null : h.works().roomOf(rat);
        }

        @Override
        public boolean canUse() {
            if (!rat.isCrew() || rat.isBroken() || rat.getTarget() != null || rat.isBurrowing() || rat.tickCount < next) {
                return false;
            }
            next = rat.tickCount + 40 + rat.getRandom().nextInt(80);
            return room() != null;
        }

        @Override
        public void start() {
            busy = 0;
            wall = null;
            RoomWork w = room();
            if (w == null) {
                return;
            }
            BoundingBox b = w.box();
            if (!b.isInside(rat.blockPosition())) {
                if (w.reachInside(rat.blockPosition())) {
                    return;                                      // at its room's work beyond its walls (a mine's shaft, its drifts): it waits there.
                                                                 // Sent home, a rat deep down the winze climbed the whole stair and walked it
                                                                 // down again for its next chore (200 to 700 ticks each, 2026-09-30)
                }
                BlockPos c = b.getCenter();
                rat.getNavigation().moveTo(c.getX() + 0.5, w.building.standingY(), c.getZ() + 0.5, 1.0);   // back to its room's floor
                return;
            }
            if (rat.isWorker() && rat.getRandom().nextInt(3) == 0) {
                for (int i = 0; i < 4 && wall == null; i++) {
                    BlockPos p = rat.blockPosition().relative(net.minecraft.core.Direction.from2DDataValue(rat.getRandom().nextInt(4)));
                    if (rat.level().getBlockState(p).isSolidRender(rat.level(), p)) {
                        wall = p;
                    }
                }
                if (wall != null) {
                    busy = 30 + rat.getRandom().nextInt(50);
                    rat.setDigging(true);
                    return;
                }
            }
            for (int i = 0; i < 10; i++) {
                BlockPos p = rat.blockPosition().offset(rat.getRandom().nextInt(11) - 5, rat.getRandom().nextInt(3) - 1, rat.getRandom().nextInt(11) - 5);
                if (b.isInside(p) && SettlerMob.standable(rat.level(), p)) {
                    rat.getNavigation().moveTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, 0.7);
                    return;
                }
            }
        }

        @Override
        public boolean canContinueToUse() {
            return rat.isCrew() && rat.getTarget() == null && !rat.isBroken() && (busy > 0 || !rat.getNavigation().isDone());
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            if (busy > 0 && wall != null) {
                busy--;
                rat.getLookControl().setLookAt(wall.getX() + 0.5, wall.getY() + 0.5, wall.getZ() + 0.5);
            }
        }

        @Override
        public void stop() {
            if (wall != null) {
                rat.setDigging(false);
            }
            busy = 0;
            wall = null;
        }
    }
}
