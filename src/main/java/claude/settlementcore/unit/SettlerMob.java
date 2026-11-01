package claude.settlementcore.unit;

import claude.settlementcore.Core;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.work.RoomWork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One of a settlement's people, as the engine sees them (Under-Empire: every Skaven, entity/SkavenUnit). The engine orders
 * them (WORK, FOLLOW, GUARD), crews its rooms with them (core/unit/CrewGoal: they carry loads in their hands), raises them
 * out of the settlement's reserve and sends them back into it, and draws crowds of them cheaply (core/client/HordeLod). A
 * race's unit extends this and says how it does each of these.
 */
public abstract class SettlerMob extends PathfinderMob {

    /** The three standing orders. */
    public enum Mode {
        WORK("to work"), FOLLOW("follow you"), GUARD("hold where they stand");   // a race may say more (the Skaven: "to work, the warren's work")

        private final String order;

        Mode(String order) {
            this.order = order;
        }

        public String order() {
            return order;
        }

        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    /** Client only: its last worked-out pose, held on the frames a far one skips, and when it was worked out (client/HordeLod). */
    public float[] lodPose;
    /** Client only: its finished model, kept between far poses (client/HordeMesh). */
    public Object lodMesh;
    public long lodPoseAt;
    /** What it is at right now as crew, for whoever asks (and test reports). */
    public String crewDoing = "";
    /** How often it has dug its way out of somewhere it was penned in (CrewGoal). */
    public int gnawedOut;
    /** How often, with nothing to do, it has walked off about its room (CrewGoal.Idle: test reports; never on a site). */
    public int milled;
    /** Told to go home by walking off its building first ({@link #leaveVia}): where to, and by when (game time); null when it isn't. Never saved: a rat never is. */
    @Nullable private BlockPos leaveSpot;
    private long leaveBy;

    protected SettlerMob(EntityType<? extends SettlerMob> type, Level level) {
        super(type, level);
    }

    public int gnawedOut() {
        return gnawedOut;
    }

    public String crewDoing() {
        return crewDoing;
    }

    /**
     * Sent home from a building on the surface: it walks to {@code spot} first (the building's landing, on natural ground outside its box) and sinks
     * there, never on the roof or inside a course; {@code by} (game time) is how long it has: then it sinks where it stands, and the settlement takes
     * it back all the same. CrewGoal does the walking. Told again, it keeps its first spot and its time.
     */
    public void leaveVia(BlockPos spot, long by) {
        if (leaveSpot == null) {
            leaveSpot = spot;
            leaveBy = by;
        }
    }

    /** Where it is walking off to before it goes home, or null. */
    @Nullable
    public BlockPos leaveSpot() {
        return leaveSpot;
    }

    /** When, in game time, it stops walking and sinks where it stands. */
    public long leaveBy() {
        return leaveBy;
    }

    /** It is where it was walking to (or out of time): the order is spent. */
    public void clearLeave() {
        leaveSpot = null;
    }

    /** Out on the surface: nothing solid overhead all the way up. Heightmap-based, so it holds before lighting settles. */
    public static boolean underOpenSky(Level level, BlockPos p) {
        return p.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
    }

    /**
     * A cell one can stand in: two free cells (every settler is under two blocks tall) on a sturdy floor, and dry. Water and
     * lava have no collision shape, so a pocket of them was "free" too: everything that picks a cell to bring a rat up in
     * (the teeming horde, the alarm's defenders, a room's crew, a march's landing) came up in the flooded pockets of a cave
     * beside the warren, where a rat floats up under the ceiling and drowns (the user's new world, 2026-09-29: ten drowned,
     * every one with its feet at the pocket's ceiling less 1.25, a slave's height; two more swam in lava).
     */
    public static boolean standable(Level level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty()
                && level.getBlockState(p.above()).getCollisionShape(level, p.above()).isEmpty()
                && level.getFluidState(p).isEmpty() && level.getFluidState(p.above()).isEmpty()
                && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP);
    }

    /** A worker (digs, crews rooms, fidgets at the walls when idle) rather than a soldier. */
    public boolean isWorker() {
        return false;
    }

    // ------------------------------------------------------------------ foes out of its reach

    /**
     * Ticks a foe may stay out of its reach before it gives the foe up: no way to it that gets there, never close enough to
     * strike, no blow landed, and no nearer for it. Vanilla's TargetGoal never asks whether a mob can get to what it sees,
     * so a foe behind a fence or across a gap was held for good, and every goal that waits on "no target" with it: on
     * 2026-09-27 a warp-fed pig's Zoglin, penned in the Beast Pens, froze their crew for good (they never path over fences).
     */
    public static final int OUT_OF_REACH = 100;
    /** How long a foe given up is let be (unless it moves {@link #MOVED_OFF} from where it was, and may be got at now). */
    public static final int LET_BE = 1200;
    private static final double MOVED_OFF = 8;
    private static final int REACH_EVERY = 10;

    private record GaveUp(long until, Vec3 where) {
    }

    /** Foes it gave up, and when and where. Never saved. */
    private final Map<UUID, GaveUp> gaveUp = new HashMap<>(2);
    /** The foe the reckoning below is of, how long it has been out of reach, and how far off it was when the reckoning began. */
    @Nullable private UUID judged;
    private int outOfReach;
    private double reckonFrom;
    private int nextReachCheck;
    /** When it last landed a blow on its foe (any weapon: a sling stone, a warp-gun, the fire). */
    private int blowAt = -10_000;
    /** When it last loosed a shot at its foe, struck or not (a slinger's stone that missed is still a shot at it). */
    private int shotAt = -10_000;
    /** A foe shot at within this many ticks is one it is getting at: a slinger looses every 35 to 50 ticks. */
    private static final int SHOT_GRACE = 60;
    /** How many foes it has given up as out of its reach (for test reports). */
    public int foesGivenUp;

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel level) {
        super.customServerAiStep(level);
        judgeReach();
    }

    /**
     * Every half second while it has a foe: out of its reach for {@link #OUT_OF_REACH} ticks, and it gives the foe up and
     * goes back to what it was at (Mob#canAttack then keeps the target goals off it). A foe its room deals with as a chore
     * (RoomWork#putsDown) is dropped at once.
     */
    private void judgeReach() {
        LivingEntity foe = getTarget();
        if (foe == null || isBurrowing()) {
            judged = null;
            return;
        }
        if (tickCount < nextReachCheck) {
            return;
        }
        nextReachCheck = tickCount + REACH_EVERY;                // a cooldown, never tickCount % n
        if (givenUp(foe) || leftToItsRoom(foe)) {
            setTarget(null);                                     // set by hand, or held from before: no target goal drops it
            judged = null;
            return;
        }
        double d = distanceTo(foe);
        if (!foe.getUUID().equals(judged) || gettingAt(foe)) {
            judged = foe.getUUID();
            outOfReach = 0;
            reckonFrom = d;
            return;
        }
        outOfReach += REACH_EVERY;
        if (outOfReach < OUT_OF_REACH) {
            return;
        }
        if (d < reckonFrom - 2) {
            outOfReach = 0;                                      // nearer all the same (a long way round): another while
            reckonFrom = d;
            return;
        }
        long now = level().getGameTime();
        if (gaveUp.size() >= 8) {
            gaveUp.values().removeIf(g -> g.until() <= now);
        }
        gaveUp.put(foe.getUUID(), new GaveUp(now + LET_BE, foe.position()));
        foesGivenUp++;
        judged = null;
        setTarget(null);
        getNavigation().stop();
    }

    /**
     * It loosed a shot at its foe now (a sling stone, whether it struck or not). A foe it fires at is one it is getting at: a slinger's stones
     * miss (about 6 in 10 strike at 10 blocks), and a slinger whose stones missed for 100 ticks gave its foe up and let it be for 1200.
     */
    public void foeShotAt() {
        shotAt = tickCount;
    }

    /** Whether it is getting at its foe: close enough to strike, a blow landed since the last look, a shot loosed at it lately, or a way there that gets there. */
    private boolean gettingAt(LivingEntity foe) {
        if (isWithinMeleeAttackRange(foe) || tickCount - blowAt <= REACH_EVERY || tickCount - shotAt <= SHOT_GRACE) {
            return true;
        }
        Path p = getNavigation().getPath();
        return p != null && p.canReach() && p.getTarget().distSqr(foe.blockPosition()) <= 9;
    }

    /**
     * A foe it gave up as out of its reach, still where it was then. One that comes within a blow of it, or strikes it (a
     * zombie that broke through the wall), is a foe again at once.
     */
    public boolean givenUp(LivingEntity foe) {
        if (gaveUp.isEmpty()) {
            return false;
        }
        GaveUp g = gaveUp.get(foe.getUUID());
        if (g == null) {
            return false;
        }
        if (level().getGameTime() < g.until() && foe.position().distanceToSqr(g.where()) < MOVED_OFF * MOVED_OFF
                && !isWithinMeleeAttackRange(foe) && getLastHurtByMob() != foe) {
            return true;
        }
        gaveUp.remove(foe.getUUID());
        return false;
    }

    /** A foe its own room deals with as one of its chores, not a fight (the Beast Pens' crew put a feral beast down). */
    public boolean leftToItsRoom(LivingEntity foe) {
        if (!isCrew()) {
            return false;
        }
        Settlement h = settlement();
        RoomWork w = h == null ? null : h.works().roomOf(this);
        return w != null && w.putsDown(foe);
    }

    /** Never a foe it gave up, nor one its room puts down: the target goals ask this, and let such a foe be. */
    @Override
    public boolean canAttack(LivingEntity target) {
        return super.canAttack(target) && !givenUp(target) && !leftToItsRoom(target);
    }

    /** Blows landed, whatever the weapon: a slinger or a weapon team that hits from afar is getting at its foe. */
    @EventBusSubscriber(modid = Core.MODID)
    static final class Blows {
        @SubscribeEvent
        static void landed(LivingDamageEvent.Post e) {
            if (e.getSource().getEntity() instanceof SettlerMob s && e.getInflictedDamage() > 0 && s.getTarget() == e.getEntity()) {
                s.blowAt = s.tickCount;
            }
        }
    }

    // ------------------------------------------------------------------ what a race's unit says about itself

    /** The settlement it belongs to, if that is loaded. */
    @Nullable
    public abstract Settlement settlement();

    /** Bound to this settlement (its people, fed from its stores). */
    public abstract void bindTo(Settlement settlement);

    /** Its own pace, about 0.8-1.25: how fast it works and how fast its loops play. */
    public abstract float tempo();

    /** Ticks it takes to fall into a looping animation: staggers the loops' starts. */
    public abstract int animLag();

    public abstract Mode getMode();

    public abstract void setMode(Mode m);

    /** One of a work room's crew. */
    public abstract boolean isCrew();

    /** Up out of the reserve to work the room whose door is {@code room}. */
    public abstract void makeCrew(BlockPos room);

    /** The door of the room it works, if it is crew. */
    @Nullable
    public abstract BlockPos crewRoom();

    /** What it carries for its room (in its hand), or empty. */
    public abstract ItemStack carried();

    /** A load into its hand, or empty for its own tool back. */
    public abstract void carry(ItemStack load);

    /** Whether it is at the dig or crew work animation. */
    public abstract boolean isDigging();

    public abstract void setDigging(boolean d);

    /** Back down into the settlement's reserve. */
    public abstract void goHome();

    /** On its way back into the reserve. */
    public abstract boolean isHomeward();

    /** Going through the ground (it can't be hurt meanwhile). */
    public abstract boolean isBurrowing();

    /** Down into the ground and up again at x, y, z. */
    public abstract void burrowTo(double x, double y, double z);

    /** Up out of the ground at x, y, z. */
    public abstract void emergeAt(double x, double y, double z);

    /** Broken: fleeing, no use for work or a fight. */
    public abstract boolean isBroken();

    /** No settlement's any more (starved out). */
    public abstract boolean isRogue();

    /** Courage (morale) by {@code amount}. */
    public abstract void hearten(float amount);

    /** Its running goals, for test reports. */
    public abstract String runningGoals();
}
