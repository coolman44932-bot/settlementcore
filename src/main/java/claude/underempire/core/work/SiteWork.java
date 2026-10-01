package claude.underempire.core.work;

import claude.underempire.core.Core;
import claude.underempire.core.room.DigJob;
import claude.underempire.core.room.RaiseJob;
import claude.underempire.core.room.Siting;
import claude.underempire.core.settlement.Building;
import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.unit.SettlerMob;
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
 * <p>The site clears the land itself, up to {@value #CLEAR_PER_SECOND} cells a second, with no builder up. Then each builder's
 * chore is a batch of up to {@value #BATCH} cells, claimed near where it stands ({@link RaiseJob#claim}), worked from a spot
 * {@link RaiseJob#standFor} picks, about {@value #TICKS_PER_BLOCK} ticks a block over its tempo, the blocks going in bite by
 * bite on its swings ({@link Chore#swing}); when it is done the next batch is claimed near where it stands. It wants
 * {@value #CREW_MAX} builders at most, and 1 per {@value #CELLS_PER_BUILDER} open cells it can hand out now.
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
    /** How many cells a second the site clears by itself. */
    public static final int CLEAR_PER_SECOND = 256;
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
    /** When each builder was last sent back round to the landing ({@link #backToTheDoor}). */
    private final Map<UUID, Long> sentAt = new HashMap<>();

    public SiteWork(Settlement settlement, RaiseJob job) {
        super(settlement, new Building(job.roomId, job.placement().box(), job.door, job.facing), job.placement());
        this.job = job;
        job.spoilsTo(settlement::takeSpoils);
    }

    public RaiseJob job() {
        return job;
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
        if (!job.working()) {
            return 0;                                            // the site clears its land itself; and a raised site is handed over
        }
        int open = job.openNow();
        return Math.max(1, Math.min(CREW_MAX, (open + CELLS_PER_BUILDER - 1) / CELLS_PER_BUILDER));
    }

    /** A builder comes up on the landing before the door (or the nearest cell to stand in there), else in the box. */
    @Override
    @Nullable
    public BlockPos comeUpAt(ServerLevel level) {
        job.ensureLoaded(level);
        Siting.Plan plan = job.plan();
        if (plan != null) {
            BlockPos at = plan.comeUpAt();
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
        if (job.clearing()) {
            job.clear(level, CLEAR_PER_SECOND, new ArrayList<>());
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
        if (!job.working()) {
            return null;
        }
        UUID id = rat.getUUID();
        BlockPos anchor = job.claim(level, id, BATCH, level.getGameTime(), rat.blockPosition());
        if (anchor == null) {
            return null;
        }
        List<DigJob.Task> batch = job.batch(id);
        List<BlockPos> cells = new ArrayList<>(batch.size());
        for (DigJob.Task t : batch) {
            cells.add(t.pos());
        }
        RaiseJob.Stand where = job.stand(level, anchor, cells, rat.blockPosition());
        if (!where.walkable()) {
            Chore leg = backToTheDoor(level, rat);
            if (leg != null) {
                return leg;
            }
        }
        BlockPos stand = where.at();
        int work = Math.max(4, Math.round(TICKS_PER_BLOCK * Math.max(1, batch.size()) / rat.tempo()));
        double reach = 2.2;
        if (stand != null) {
            double dx = stand.getX() - anchor.getX();
            double dz = stand.getZ() - anchor.getZ();
            reach = Math.sqrt(dx * dx + dz * dz) + 0.8;          // CrewGoal counts it in reach from where it stands
        }
        SiteClaim key = new SiteClaim(id, ++serial);
        serials.put(id, key.serial());
        return new Chore(job.doing(id), anchor, reach, work, key, (l, r) -> {
            effects(l, job.gnawSome(l, r.getUUID(), 1f));
            job.complete(l, r.getUUID());                        // a cell not ready yet is left open, and handed out again
            return nextChore(l, r);                              // the next batch, near where it stands
        }).standing(stand).swinging((l, r, fraction) -> effects(l, job.gnawSome(l, r.getUUID(), fraction)));
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
        BlockPos landing = comeUpAt(level);
        if (landing == null || was != null && now - was < LEG_WINDOW) {
            return null;
        }
        job.release(id);
        sentAt.put(id, now);
        return new Chore("heading round to the door", landing, 1.5, 2, null, (l, r) -> nextChore(l, r)).standing(landing);
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
            if (e.getEntity() instanceof SettlerMob rat && e.getDistance() > rat.getAttributeValue(Attributes.SAFE_FALL_DISTANCE)
                    && sureFooted(rat)) {
                e.setCanceled(true);
            }
        }

        /** Whether {@code rat}, on the ground where it is now, is a builder on its own site's footing. */
        public static boolean sureFooted(SettlerMob rat) {
            if (!rat.isCrew() || rat.level().isClientSide()) {
                return false;
            }
            Settlement home = rat.settlement();
            SiteWork site = home == null ? null : home.works().siteOf(rat);
            BoundingBox footing = site == null ? null : site.job().footing();
            return footing != null && footing.isInside(rat.blockPosition());
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
            job.release(c.rat());
        }
    }

    /** Its builder could not get to it: the batch goes back, set aside a while, and a cell set aside often is heaved in. */
    @Override
    public void shelve(@Nullable Object key, long now) {
        if (key instanceof SiteClaim c && current(c)) {
            job.shelve(c.rat(), now);
        }
    }

    /** A builder is gone (killed, sent home, dismissed): whatever batch it held goes back. */
    public void let(UUID id) {
        serials.remove(id);
        sentAt.remove(id);
        job.release(id);
    }

    // --- what it shows -----------------------------------------------------------------------------------------------

    @Override
    public Gauge gauge(ServerLevel level) {
        job.ensureLoaded(level);
        return new Gauge(job.progress(), job.state());
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
        out.add(Component.literal(room().displayName() + " (being raised): " + job.state() + ", " + Math.round(job.progress() * 100) + "% done"
                + (job.heaved() > 0 ? ", " + job.heaved() + " heaved into place" : "")
                + (job.spared() > 0 ? ", " + job.spared() + " of your blocks left standing" : "")).withStyle(ChatFormatting.DARK_GREEN));
    }
}
