package claude.settlementcore.work;

import claude.settlementcore.Core;
import claude.settlementcore.room.DigJob;
import claude.settlementcore.room.RaiseJob;
import claude.settlementcore.room.Siting;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.unit.SettlerMob;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingFallEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A building being raised on the surface (docs/plans/surface-builder.md, section 2): its {@link RaiseJob} and the settlers who
 * work it, crewed out of the reserve by {@code workerType} and running the engine's CrewGoal as any room's crew do. The
 * Works keep it beside the rooms, at its door's key, until it is done; then it is handed over (Works#second), and its crew
 * become the finished room's.
 *
 * <p>One builder comes up first and clears the land (the detail session's notes on the films of 2026-10-01: the oak had vanished with no one there,
 * and then it crumbled by itself): it walks to the foot of what is to be cut and swings at it, the trees coming down, top-down, with each swing, each
 * cell with its dust and sound ({@link RaiseJob#clearNext}, {@link RaiseJob#clearSome}; cells already as they should be are free), about
 * {@value #CLEAR_TICKS_PER_BLOCK} tick a cell, so a tree of 50 blocks is down in a second or two. With no builder to come (an empty reserve), or none having
 * got on in {@value #CLEAR_PATIENCE} ticks, the site clears itself, {@value #CLEAR_SELF_PER_SECOND} cells a second. Then each builder's
 * chore is a batch of up to {@value #BATCH} cells, claimed near where it stands ({@link RaiseJob#claim}), worked from a spot
 * {@link RaiseJob#standFor} picks, about {@value #TICKS_PER_BLOCK} ticks a block over its tempo, the blocks going in bite by
 * bite on its swings ({@link Chore#swing}); when it is done the next batch is claimed near where it stands. It wants
 * {@value #CREW_MAX} builders at most, and 1 per {@value #CELLS_PER_BUILDER} open cells it can hand out now.
 *
 * <p>A site that was called off ({@code Works#cancel}) is torn down by the same builders, the same way round: the cells they laid come down, top first,
 * a batch at a time ({@link RaiseJob#tearClaim}), {@value #TEAR_TICKS_PER_BLOCK} ticks a block, each with its dust and sound. When no builder can come (the
 * reserve is empty) or none has taken a cell down for {@value #TEAR_PATIENCE} ticks, the site takes {@value #TEAR_SELF_PER_SECOND} cells a second down
 * itself, so it is never left half taken down.
 *
 * <p>Never saved as a room ({@link Works#save} writes its job under {@code sites}); a builder's claim is keyed by who holds it
 * and which of its batches the chore is for ({@link SiteClaim}), so a chore's end never lets go of the batch it chained on to.
 *
 * <p>Its builders are sure-footed ({@link SureFooting}, the user's pick 4): crowding can still shove one off a course, and a
 * builder who falls inside {@link RaiseJob#footing} takes no damage.
 */
public final class SiteWork extends RoomWork {

    /** The most builders a site takes. */
    public static final int CREW_MAX = 8;
    /** One builder for this many open cells it can hand out now. */
    public static final int CELLS_PER_BUILDER = 12;
    /** A builder's batch: up to this many cells. */
    public static final int BATCH = 6;
    /** Ticks a block takes a builder of tempo 1. */
    public static final int TICKS_PER_BLOCK = 6;
    /** The most cells a call of {@link RaiseJob#clear} does (the tests and the finish clear a site whole, unpaced). */
    public static final int CLEAR_PER_SECOND = 256;
    /** The most cells of what is to be cut down one builder is sent to at once: a whole tree; a bigger one (up to Siting.TREE_MAX) is felled in batches, top-down, from its one foot. */
    public static final int CLEAR_BATCH = 60;
    /** Ticks a cell takes the builder at the trees: a tree of 50 blocks is down in about 2.5 s. */
    public static final int CLEAR_TICKS_PER_BLOCK = 1;
    /** The cells a second a site clears itself when no builder can come. */
    public static final int CLEAR_SELF_PER_SECOND = 20;
    /** How long, in ticks, a site's clearing may go with no cell done before the site clears itself. */
    public static final int CLEAR_PATIENCE = 400;
    /** Ticks a block takes a builder of tempo 1 to take down. */
    public static final int TEAR_TICKS_PER_BLOCK = 4;
    /** The cells a second a site with no builder takes down itself. */
    public static final int TEAR_SELF_PER_SECOND = 4;
    /** How long, in ticks, a site being torn down may go with no cell coming down before it takes itself down. */
    public static final int TEAR_PATIENCE = 200;
    /**
     * The site's seconds (counted only while it is worked: a site no one is near keeps no clock) with builders up, none holding
     * a batch and no open cell to hand out: then the window's lead, its lowest open cell that isn't a hanging one, is heaved into
     * place ({@link RaiseJob#heaveFront}), so nothing stalls for good (a cell whose hold was broken under it, a cell set aside as
     * out of reach with nothing else left).
     */
    public static final int DRY_SECONDS = 3;
    /**
     * How long, in ticks, a builder that was sent round to the landing ({@link #backToTheDoor}) and still has no stand it can walk to is let try the
     * best stand there is, before it is sent round again.
     */
    public static final int LEG_WINDOW = 600;

    /** A builder's claim on the site: the chore for its {@code serial}th batch. */
    public record SiteClaim(UUID rat, int serial) {
    }

    private final RaiseJob job;
    private final Map<UUID, Integer> serials = new HashMap<>();
    private int serial;
    /** Its worked seconds in a row with builders up and nothing to hand them. */
    private int dry;
    /** The builder sent to cut down the trees, while it is at it; the clearing is one builder's, the cells come down in order. */
    @Nullable private UUID cutter;
    /** How many cells the clearing had done when the site last looked, and when that last changed (game time): a clearing that goes nowhere is the site's own. */
    private int clearSeen = -1;
    private long clearAt = -1;
    /**
     * The site's builders were up and got nowhere for {@link #CLEAR_PATIENCE} ticks, and it has begun to clear the land itself: once begun it goes on, 20 cells a second (its own
     * cells change the count the patience watches, which began the 400 ticks again after each 20). The cutter, if there is one, goes on cutting alongside: both fell the
     * cells in the job's order. Not saved. An empty reserve doesn't set it: that is the site's own clearing for as long as the reserve is empty, and a builder takes the clearing back
     * when it refills.
     */
    private boolean clearSelf;
    /** When each builder was last sent back round to the landing ({@link #backToTheDoor}). */
    private final Map<UUID, Long> sentAt = new HashMap<>();
    /** The falls {@link SureFooting} took the damage of since the site was begun (or loaded: not saved), and the longest. */
    private int shoves;
    /** Cells handed out to be laid from the foot of the work ({@link #lift}). */
    private int lifts;
    private float hardestShove;

    public SiteWork(Settlement settlement, RaiseJob job) {
        super(settlement, new Building(job.roomId, job.placement().box(), job.door, job.facing), job.placement());
        this.job = job;
        job.spoilsTo(settlement::takeSpoils);
    }

    public RaiseJob job() {
        return job;
    }

    /**
     * How many falls of its builders {@link SureFooting} took the damage of (each was more than the builder's safe fall, so each would have hurt
     * it): the crewed tests fail on any, since a builder shoved off a course isn't hurt and nothing else would say so.
     */
    public int shoves() {
        return shoves;
    }

    /** The longest of those falls, in blocks (the event's own distance, exact). */
    public float hardestShove() {
        return hardestShove;
    }

    /** Whether every cell is done: the Works hand it over. */
    public boolean raised() {
        return job.isDone();
    }

    @Override
    public void opened(ServerLevel level) {
        job.ensureLoaded(level);
    }

    @Override
    public int crewWanted() {
        if (job.tearing()) {
            int open = job.tearOpen();                           // coming down: as many builders as cells to take down, as for the laying
            return open == 0 ? 0 : Math.max(1, Math.min(CREW_MAX, (open + CELLS_PER_BUILDER - 1) / CELLS_PER_BUILDER));
        }
        if (job.clearing()) {
            return 1;                                            // one builder, to open the site: the clearing begins when it is up
        }
        if (!job.working()) {
            return 0;                                            // a raised site is handed over
        }
        int open = job.openNow();
        return Math.max(1, Math.min(CREW_MAX, (open + CELLS_PER_BUILDER - 1) / CELLS_PER_BUILDER));
    }

    /** A builder comes up on the landing before the door (a wall's: inside it, by the next column to lay), or the nearest cell to stand in there, else in the box. */
    @Override
    @Nullable
    public BlockPos comeUpAt(ServerLevel level) {
        job.ensureLoaded(level);
        BlockPos at = job.comeUpNear(level);
        if (at != null) {
            for (int r = 0; r <= 3; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue;
                        }
                        for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3, 4, -4}) {
                            BlockPos p = at.offset(dx, dy, dz);
                            if (level.isLoaded(p) && SettlerMob.standable(level, p)) {
                                return p;
                            }
                        }
                    }
                }
            }
        }
        return Works.standable(level, this);
    }

    /** A builder with nothing to do waits where it stands: the box is the building going up, full of cells still to be laid. */
    @Override
    public boolean millsAbout() {
        return false;
    }

    /** Its builders work all over its area, not only in the box: none of them has strayed there (Works#second, the idle goal). */
    @Override
    public boolean reachInside(BlockPos p) {
        BoundingBox f = job.footprint();
        return f != null ? f.inflatedBy(2).isInside(p) : box().inflatedBy(16).isInside(p);
    }

    @Override
    public void second(ServerLevel level, int crewHere) {
        job.ensureLoaded(level);
        long now = level.getGameTime();
        if (job.tearing()) {
            // called off: its builders take it down (nextTearChore); with none to come, or none taking a cell down for a while, it takes itself down
            job.tearEnsure(level);
            boolean nobody = crewHere == 0 && heart.reserveWorkers() <= 0;
            if (nobody || job.tearSelfPaced() || now - job.tearLast() >= TEAR_PATIENCE) {
                broken(level, job.tearSelf(level, TEAR_SELF_PER_SECOND));
            }
            return;
        }
        if (job.clearing()) {
            // the clearing is a builder's (nextClearChore); with none to come, or none getting on, the site does it itself
            int done = job.finishedCount();
            if (done != clearSeen || clearAt < 0) {
                clearSeen = done;
                clearAt = now;
            }
            clearSelf |= now - clearAt >= CLEAR_PATIENCE;        // builders up that got nowhere: the site is stuck, and it stays its own
            if (clearSelf || crewHere == 0 && heart.reserveWorkers() <= 0) {
                effects(level, job.clearSome(level, CLEAR_SELF_PER_SECOND));
            }
        }
        job.heaveDue(level);
        if (crewHere > 0 && job.working() && !job.anyOut(now) && !job.anyClaimable(level)) {
            if (++dry >= DRY_SECONDS) {
                job.heaveFront(level);                           // nothing to hand out, and nobody at work: no stall
                dry = 0;
            }
        } else {
            dry = 0;
        }
    }

    @Override
    @Nullable
    public Chore nextChore(ServerLevel level, SettlerMob rat) {
        job.ensureLoaded(level);
        if (job.tearing()) {
            return nextTearChore(level, rat);
        }
        if (job.clearing()) {
            return nextClearChore(level, rat);
        }
        if (!job.working()) {
            return null;
        }
        UUID id = rat.getUUID();
        BlockPos anchor = job.claim(level, id, BATCH, level.getGameTime(), rat.blockPosition());
        if (anchor == null) {
            // nothing left to hand out: a batch held by a builder far from it (or gone) is taken over by one that stands near it
            anchor = job.takeOver(level, id, BATCH, level.getGameTime(), rat.blockPosition(), (u, p) -> {
                net.minecraft.world.entity.Entity e = level.getEntity(u);
                return e == null ? Double.MAX_VALUE : Math.sqrt(e.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5));
            });
            if (anchor == null) {
                return null;
            }
        }
        List<DigJob.Task> batch = job.batch(id);
        List<BlockPos> cells = new ArrayList<>(batch.size());
        for (DigJob.Task t : batch) {
            cells.add(t.pos());
        }
        RaiseJob.Stand where = job.stand(level, anchor, cells, rat.blockPosition());
        BlockPos foot = where.walkable() ? null : lift(level, anchor, cells, rat);
        if (!where.walkable() && foot == null) {
            Chore leg = backToTheDoor(level, rat);               // no foot from here: round to the landing first (a foot found needs no leg)
            if (leg != null) {
                return leg;
            }
        }
        BlockPos stand = foot != null ? foot : where.at();
        int work = Math.max(4, Math.round(TICKS_PER_BLOCK * Math.max(1, batch.size()) / rat.tempo()));
        double reach = 2.2;
        if (stand != null) {
            double dx = stand.getX() - anchor.getX();
            double dz = stand.getZ() - anchor.getZ();
            reach = Math.sqrt(dx * dx + dz * dz) + 0.8;          // CrewGoal counts it in reach from where it stands
        }
        SiteClaim key = new SiteClaim(id, ++serial);
        serials.put(id, key.serial());
        Chore c = new Chore(job.doing(id), anchor, reach, work, key, (l, r) -> {
            effects(l, job.gnawSome(l, r.getUUID(), 1f));
            job.complete(l, r.getUUID());                        // a cell not ready yet is left open, and handed out again
            return nextChore(l, r);                              // the next batch, near where it stands
        });
        c.standing(stand).swinging((l, r, fraction) -> effects(l, job.gnawSome(l, r.getUUID(), fraction))).whileHeld(() -> job.holds(id));
        return foot != null ? c.lifted() : c;
    }

    /**
     * Where a batch no stand the builder can walk to reaches is laid from (the walk was told): the foot of the work ({@link RaiseJob#footFor},
     * within {@value RaiseJob#FOOT_ACROSS} across), at the usual pace, the reach up waived; no leg round to the landing first (the review of
     * 6907d91: a wasted walk every LEG_WINDOW). A wall higher than any floor round it (the Hold Gate's 38-tall towers over a ledge at 3) was
     * stood out 3 x 400 ticks a cell and heaved, 0.4 cells a second. Null when there is no foot: the leg, then the old stand-out and heave.
     */
    @Nullable
    private BlockPos lift(ServerLevel level, BlockPos anchor, List<BlockPos> cells, SettlerMob rat) {
        BlockPos foot = job.footFor(level, anchor, cells, rat.blockPosition());
        if (foot != null) {
            lifts += cells.size();
        }
        return foot;
    }

    /** Cells handed out to be laid from the foot of the work ({@link #lift}) since the site was loaded: for the tests. */
    public int lifts() {
        return lifts;
    }

    /**
     * The builder's chore while the site clears: walk to the foot of what is to be cut ({@link RaiseJob#clearFoot}: a tree's trunk, even when the next
     * {@value #CLEAR_BATCH} cells, top-down, are only its upper part), stand where the work is in reach, and swing at it; the cells come down in order with
     * the swings, each with its dust and sound, and what is left of the batch when the chore ends. A site called off while it swings fells nothing more
     * ({@link RaiseJob#clearSome}). Null for any builder but the one that was sent, or when nothing is left to cut.
     */
    @Nullable
    private Chore nextClearChore(ServerLevel level, SettlerMob rat) {
        UUID id = rat.getUUID();
        if (cutter != null && !cutter.equals(id)) {
            return null;
        }
        List<BlockPos> cells = job.clearNext(level, CLEAR_BATCH);
        if (cells.isEmpty()) {
            cutter = null;
            return null;
        }
        BlockPos anchor = job.clearFoot(level, cells, rat.blockPosition());   // the foot of what is cut, not the lowest of these cells: a tree's crown, when it has more than a batch
        BlockPos stand = job.stand(level, anchor, cells, rat.blockPosition()).at();
        int n = cells.size();
        int work = Math.max(4, Math.round(CLEAR_TICKS_PER_BLOCK * n / rat.tempo()));
        double reach = 2.2;
        if (stand != null) {
            double dx = stand.getX() - anchor.getX();
            double dz = stand.getZ() - anchor.getZ();
            reach = Math.sqrt(dx * dx + dz * dz) + 0.8;
        }
        SiteClaim key = new SiteClaim(id, ++serial);
        serials.put(id, key.serial());
        cutter = id;
        int[] cut = {0};
        return new Chore("clearing the site", anchor, reach, work, key, (l, r) -> {
            effects(l, job.clearSome(l, Math.max(0, n - cut[0])));
            cutter = null;
            return nextChore(l, r);
        }).standing(stand).swinging((l, r, fraction) -> {
            int upTo = (int) Math.ceil(n * fraction);
            effects(l, job.clearSome(l, Math.max(0, upTo - cut[0])));
            cut[0] = Math.max(cut[0], upTo);
        });
    }

    /** A builder's next chore while the site is torn down: a batch of the cells that come down next, from a stand in reach of them, taken out bite by bite. */
    @Nullable
    private Chore nextTearChore(ServerLevel level, SettlerMob rat) {
        UUID id = rat.getUUID();
        BlockPos anchor = job.tearClaim(level, id, BATCH, level.getGameTime(), rat.blockPosition());
        if (anchor == null) {
            return null;                                         // the course that is coming down is all held: this one waits for the next
        }
        List<BlockPos> cells = job.tearBatch(id);
        RaiseJob.Stand where = job.stand(level, anchor, cells, rat.blockPosition());
        BlockPos foot = where.walkable() ? null : lift(level, anchor, cells, rat);
        if (!where.walkable() && foot == null) {
            Chore leg = backToTheDoor(level, rat);               // no foot from here: round to the landing first (a foot found needs no leg)
            if (leg != null) {
                return leg;
            }
        }
        BlockPos stand = foot != null ? foot : where.at();
        int work = Math.max(3, Math.round(TEAR_TICKS_PER_BLOCK * Math.max(1, cells.size()) / rat.tempo()));
        double reach = 2.2;
        if (stand != null) {
            double dx = stand.getX() - anchor.getX();
            double dz = stand.getZ() - anchor.getZ();
            reach = Math.sqrt(dx * dx + dz * dz) + 0.8;
        }
        SiteClaim key = new SiteClaim(id, ++serial);
        serials.put(id, key.serial());
        Chore c = new Chore("pulling the building down", anchor, reach, work, key, (l, r) -> {
            broken(l, job.tearSome(l, r.getUUID(), 1f));
            broken(l, job.tearComplete(l, r.getUUID()));
            return nextChore(l, r);
        });
        c.standing(stand).swinging((l, r, fraction) -> broken(l, job.tearSome(l, r.getUUID(), fraction)));
        return foot != null ? c.lifted() : c;
    }

    /**
     * A builder that has no stand it can walk to for the batch it was handed ({@link RaiseJob#stand}: outside the back wall of a closed hall with
     * one door, the way in is more than {@value RaiseJob#WALK_STEPS} steps off) gives the batch back at once, and is sent round to the landing, the
     * way the builders come in: from there the walk to the work inside is short, and whoever can walk to the batch may take it meanwhile. Before, it
     * took the batch and stood out its 400 ticks (CrewGoal's give-up) on a path to nowhere, the cells were set aside, and the site's stall rule heaved
     * them (the last roof cells of a closed hall with one door, whenever a builder was outside its back wall then). Null when it was sent already within the last {@value #LEG_WINDOW}
     * ticks, or there is no landing: it then tries the best stand there is, as before.
     */
    @Nullable
    private Chore backToTheDoor(ServerLevel level, SettlerMob rat) {
        UUID id = rat.getUUID();
        long now = level.getGameTime();
        Long was = sentAt.get(id);
        if (was != null && now - was < LEG_WINDOW) {
            return null;                                         // sent once in the window: before the landing's scan (the review of a235bf3)
        }
        BlockPos landing = comeUpAt(level);
        if (landing == null) {
            return null;
        }
        job.release(id);
        job.tearRelease(id);
        sentAt.put(id, now);
        return new Chore("heading round to the door", landing, 1.5, 2, null, (l, r) -> nextChore(l, r)).standing(landing);
    }

    /** The cells a teardown took out: each breaks as what it was (the destroy event: its sound and its dust), though the ground the land has may stand there now. */
    private static void broken(ServerLevel level, List<DigJob.Crumb> crumbs) {
        for (DigJob.Crumb c : crumbs) {
            if (!c.was().isAir() && c.was().getFluidState().isEmpty()) {
                level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, c.pos(), Block.getId(c.was()));
            }
        }
    }

    /** The blocks that went in on a swing: a cut's dust and sound, a laid block's sound and a puff of it. */
    private static void effects(ServerLevel level, List<DigJob.Crumb> crumbs) {
        for (DigJob.Crumb c : crumbs) {
            BlockPos p = c.pos();
            BlockState now = level.getBlockState(p);
            if (now == c.was()) {
                continue;
            }
            if (now.isAir()) {
                if (!c.was().isAir() && c.was().getFluidState().isEmpty()) {
                    level.levelEvent(LevelEvent.PARTICLES_DESTROY_BLOCK, p, Block.getId(c.was()));
                }
                continue;
            }
            SoundType st = now.getSoundType();
            level.playSound(null, p, st.getPlaceSound(), SoundSource.BLOCKS, (st.getVolume() + 1f) / 2f, st.getPitch() * 0.8f);
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, now), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0.02);
        }
    }

    // --- sure footing --------------------------------------------------------------------------------------------------

    /**
     * No fall damage for a builder on its own site (the user's pick 4, 2026-09-30: "no fall damage"; LivingEntity#causeFallDamage fires
     * {@code LivingFallEvent}, and cancelling it is no fall). The builder must be a settler who is crew of a site still being raised, and
     * lands inside that site's {@link RaiseJob#footing}: its whole area, 2 wider all round. Players and every other mob fall as ever,
     * and so does a builder anywhere else.
     *
     * <p>Cheap at horde scale: nearly every landing in the world is not a builder's, and is let go by an {@code instanceof} and a
     * field; a fall that does no damage anyway ends before any lookup; and the site is found by the builder's own id and its door
     * (two map lookups in the settlement's Works), never by a scan of the sites.
     */
    @EventBusSubscriber(modid = Core.MODID)
    public static final class SureFooting {

        private SureFooting() {
        }

        @SubscribeEvent
        public static void landed(LivingFallEvent e) {
            if (e.getEntity() instanceof SettlerMob rat && e.getDistance() > rat.getAttributeValue(Attributes.SAFE_FALL_DISTANCE)) {
                SiteWork site = footedAt(rat);
                if (site != null) {
                    site.shoves++;                               // counted, for the tests: the fall is no harm to anyone, so nothing else shows it
                    site.hardestShove = Math.max(site.hardestShove, (float) e.getDistance());
                    e.setCanceled(true);
                }
            }
        }

        /** Whether {@code rat}, on the ground where it is now, is a builder on its own site's footing. */
        public static boolean sureFooted(SettlerMob rat) {
            return footedAt(rat) != null;
        }

        /** The site {@code rat} is a builder of, when it stands on that site's footing; else null. */
        @Nullable
        private static SiteWork footedAt(SettlerMob rat) {
            if (!rat.isCrew() || rat.level().isClientSide()) {
                return null;
            }
            Settlement home = rat.settlement();
            SiteWork site = home == null ? null : home.works().siteOf(rat);
            BoundingBox footing = site == null ? null : site.job().footing();
            return footing != null && footing.isInside(rat.blockPosition()) ? site : null;
        }
    }

    // --- builders' claims: keyed by batch, never kept in RoomWork's own claims ---------------------------------------------

    /** Whether {@code c} is its builder's batch now (a chore that chained on to the next has a newer one). */
    private boolean current(SiteClaim c) {
        Integer s = serials.get(c.rat());
        return s != null && s == c.serial();
    }

    @Override
    public void claim(@Nullable Object key, long now) {
        // a builder's batch is held by the job (RaiseJob#claim), per builder: nothing to keep here
    }

    @Override
    public void release(@Nullable Object key) {
        if (key instanceof SiteClaim c && current(c)) {
            if (c.rat().equals(cutter)) {
                cutter = null;
            }
            job.release(c.rat());
            job.tearRelease(c.rat());
        }
    }

    /** Its builder could not get to it: the batch goes back, set aside a while, and a cell set aside often is heaved in. */
    @Override
    public void shelve(@Nullable Object key, long now) {
        if (key instanceof SiteClaim c && current(c)) {
            if (c.rat().equals(cutter)) {
                cutter = null;
            }
            job.shelve(c.rat(), now);
            job.tearRelease(c.rat());                            // a batch to take down just goes back: the site takes itself down if no one can
        }
    }

    /** A builder is gone (killed, sent home, dismissed): whatever batch it held goes back. */
    public void let(UUID id) {
        serials.remove(id);
        sentAt.remove(id);
        if (id.equals(cutter)) {
            cutter = null;
        }
        job.release(id);
        job.tearRelease(id);
    }

    // --- what it shows -----------------------------------------------------------------------------------------------

    @Override
    public Gauge gauge(ServerLevel level) {
        job.ensureLoaded(level);
        return new Gauge(job.tearing() ? job.tearProgress() : job.builtShare(), job.state());
    }

    @Override
    public void wants(ServerLevel level, List<String> needs, List<String> boosts) {
        int shelved = job.shelvedNow(level.getGameTime());
        if (shelved > 0) {
            needs.add("a way to " + shelved + " block" + (shelved == 1 ? "" : "s") + " the builders can't reach");
        }
    }

    @Override
    public void describe(ServerLevel level, List<Component> out) {
        job.ensureLoaded(level);
        if (job.tearing()) {
            out.add(Component.literal(room().displayName() + " (coming down): " + job.tornCount() + " of " + job.tearTotal() + " cells down").withStyle(ChatFormatting.DARK_GREEN));
            return;
        }
        out.add(Component.literal(room().displayName() + " (being raised): " + job.state() + ", " + Math.round(job.builtShare() * 100) + "% built"
                + (job.heaved() > 0 ? ", " + job.heaved() + " heaved into place" : "")
                + (job.spared() > 0 ? ", " + job.spared() + " of your blocks left standing" : "")).withStyle(ChatFormatting.DARK_GREEN));
    }
}
