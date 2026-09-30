package claude.underempire.core.work;

import claude.underempire.core.unit.SettlerMob;
import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.settlement.Building;
import claude.underempire.core.room.Rooms;
import claude.underempire.core.room.PlanPlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * What the clan does in one work room (the ones ue_survival builds): the room's crew, the chores it
 * hands them (entity/CrewGoal), what it does by itself once a second or once a tick, and the night's
 * work it catches up on while the Warlord sleeps in the Den (Works#nightsWork). One per finished room;
 * the Heart's {@link Works} keeps them.
 */
public abstract class RoomWork {

    public final Settlement heart;
    public final Building building;
    public final PlanPlacement placement;
    private final Map<Object, Long> claims = new HashMap<>();
    /** When the room last looked round itself for its bins and furnaces (they can be added, moved, broken). */
    private long scannedAt = Long.MIN_VALUE / 2;          // /2: now - scannedAt must not overflow
    /** How often it looks again, in ticks. */
    protected static final int RESCAN = 200;

    protected RoomWork(Settlement heart, Building building, PlanPlacement placement) {
        this.heart = heart;
        this.building = building;
        this.placement = placement;
    }

    /** The room's door: what its crew, its saved state and the Works know it by. */
    public BlockPos key() {
        return building.door();
    }

    public BoundingBox box() {
        return building.box();
    }

    public Rooms.Room room() {
        return placement.room();
    }

    /** Whether {@code p} is somewhere the room's work takes its crew beyond its walls (the Ore Mine's drifts). */
    public boolean reachInside(BlockPos p) {
        return false;
    }

    /** How many of its crew it wants at work. */
    public abstract int crewWanted();

    /** Who its crew are: slaves out of the horde, unless the room says otherwise (the Workshop's Warlock Engineer). */
    public EntityType<? extends SettlerMob> crewType() {
        return heart.workerType();
    }

    /** Whether its crew come out of the horde (and go back into it). */
    public boolean crewFromHorde() {
        return true;
    }

    /** One of its crew has just come up out of the tunnels (a Watch-Post's are made sentries). */
    public void crewSpawned(SettlerMob rat) {
    }

    /** The first time the room is worked after it is dug (the Beast Pens take in their stock). */
    public void opened(ServerLevel level) {
    }

    /** Once a second, while the room is loaded; {@code crewHere} of its crew are up and about in it. */
    public void second(ServerLevel level, int crewHere) {
    }

    /** Every tick, while the room is loaded (the bellows). */
    public void tick(ServerLevel level, int crewHere) {
    }

    /** The next thing for one of the crew to do, or null for nothing (it idles about the room). */
    @Nullable
    public abstract Chore nextChore(ServerLevel level, SettlerMob rat);

    /**
     * A foe the room's crew deal with as one of their chores, not a fight: they never take it for a target
     * (SettlerMob#canAttack), so it cannot keep them off their work (the Beast Pens put a feral beast down over the fence).
     */
    public boolean putsDown(net.minecraft.world.entity.LivingEntity foe) {
        return false;
    }

    /** {@code seconds} of the room's work at once (while the Warlord slept in the Den); what it did goes in {@code report}. */
    public void nightsWork(ServerLevel level, int seconds, List<Component> report) {
    }

    /**
     * One of the room's supply bins, and what the horde keeps it stocked with out of the Clan Hoard: {@code keep} of each
     * kind {@code what} takes (Works#supply, every haul). {@code label} names it for the far-peeker.
     */
    public record Supply(String label, List<BlockPos> bins, Predicate<ItemStack> what, int keep) {
    }

    /** Gear the room wants made for it in the Warlock's Workshop: of {@code item}, {@code keep} in the clan, and why ("the Throne Hall's guard"). */
    public record Gear(ItemStack item, int keep, String why) {
    }

    /** What the room wants the Warlock's Workshop to keep made for it (WorkOrders: the clan keeps the most any asks). */
    public List<Gear> gear(ServerLevel level) {
        return List.of();
    }

    /** What the horde brings this room out of the Clan Hoard (user, 2026-09-26: "they dont grab the materials they need from the hoard"). */
    public List<Supply> supplies(ServerLevel level) {
        return List.of();
    }

    /** A gauge for the far-peeker (compat/jade): how far along the room's main work is. */
    public record Gauge(float fill, String label) {
    }

    /**
     * What the room is short of, for the Warlord who looks at it (compat/jade FarPeeker): {@code needs}, what has it
     * stalled or soon will; {@code boosts}, what would make it work faster or better. A few words each.
     */
    public void wants(ServerLevel level, List<String> needs, List<String> boosts) {
    }

    /** The room's gauge for the far-peeker, or null. */
    @Nullable
    public Gauge gauge(ServerLevel level) {
        return null;
    }

    /** An output bin with no slot free: the horde's haul empties it into the Clan Hoard, unless there is no room there either. */
    protected static void roomIn(ServerLevel level, List<BlockPos> bins, String name, List<String> needs) {
        for (BlockPos p : bins) {
            net.minecraft.world.Container c = Bins.at(level, p);
            if (c == null) {
                return;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).isEmpty()) {
                    return;
                }
            }
        }
        if (!bins.isEmpty()) {
            needs.add("room in the " + name + " (is the Hoard full?)");
        }
    }

    /** Lines for the Heart's report. */
    public void describe(ServerLevel level, List<Component> out) {
    }

    public CompoundTag save() {
        return new CompoundTag();
    }

    public void load(CompoundTag tag) {
    }

    // ------------------------------------------------------------------ helpers

    /** Look round the room again if it is time (bins, furnaces, plots): {@link #scan} does the looking. */
    protected final void rescan(ServerLevel level) {
        long now = level.getGameTime();
        if (now - scannedAt >= RESCAN || now < scannedAt) {
            scannedAt = now;
            scan(level);
        }
    }

    /** Force a fresh look on the next {@link #rescan}. */
    public final void rescanSoon() {
        scannedAt = Long.MIN_VALUE / 2;
    }

    protected void scan(ServerLevel level) {
    }

    /** A spot of the room's template (Rooms.Station) where it is in the world, and which way it faces there. */
    public record Spot(BlockPos pos, Direction facing) {
    }

    protected List<Spot> stations(String role) {
        List<Spot> out = new ArrayList<>();
        for (Rooms.Station s : room().stations()) {
            if (s.role().equals(role)) {
                Direction f = s.facing().getAxis().isHorizontal() ? placement.rotation().rotate(s.facing()) : s.facing();
                out.add(new Spot(placement.world(new BlockPos(s.x(), s.y(), s.z())), f));
            }
        }
        return out;
    }

    /** Whether no other rat is on this chore (claims lapse after 30 s, in case a rat is lost). */
    public boolean free(@Nullable Object key, long now) {
        if (key == null) {
            return true;
        }
        Long until = claims.get(key);
        return until == null || until < now;
    }

    public void claim(@Nullable Object key, long now) {
        if (key != null) {
            claims.put(key, now + 600);
        }
    }

    public void release(@Nullable Object key) {
        if (key != null) {
            claims.remove(key);
        }
    }

    /** Where one of its crew comes up out of the tunnels: a cell to stand in near the room's middle, or null. */
    @Nullable
    public BlockPos comeUpAt(ServerLevel level) {
        return Works.standable(level, this);
    }

    /**
     * A chore the crew could not get to (a beast out of the hook-pole's reach behind its fence): set aside for 30
     * seconds, so the next pick is another one rather than the same one again, over and over.
     */
    public void shelve(@Nullable Object key, long now) {
        if (key != null) {
            claims.put(key, now + 600);
        }
    }

    /**
     * A load carried: from the container at {@code from}, up to {@code n} of the first thing {@code pick}
     * takes there, to {@code to}, where {@code put} stores it (returning whatever did not fit, which
     * goes back where it came from).
     */
    protected Chore carry(String what, BlockPos from, Predicate<ItemStack> pick, int n, BlockPos to, Object claim,
                          BiFunction<ServerLevel, ItemStack, ItemStack> put) {
        return new Chore(what, from, 2.6, 6, claim, (level, rat) -> {
            ItemStack got = Bins.take(level, List.of(from), pick, n);
            if (got.isEmpty()) {
                return null;
            }
            rat.carry(got);
            return new Chore(what, to, 2.6, 6, claim, (l2, r2) -> {
                ItemStack rest = put.apply(l2, r2.carried());
                r2.carry(ItemStack.EMPTY);
                if (!rest.isEmpty()) {
                    Container back = Bins.at(l2, from);
                    ItemStack left = back == null ? rest : Bins.insert(back, rest);
                    if (!left.isEmpty()) {
                        r2.spawnAtLocation(left);
                    }
                }
                return null;
            }).returning(from);
        });
    }

    /** Put {@code stack} into the containers at {@code into}; what does not fit is returned. */
    protected static ItemStack store(ServerLevel level, List<BlockPos> into, ItemStack stack) {
        return Bins.insert(level, into, stack);
    }

    /**
     * Nearest to {@code from} of these containers that holds something {@code pick} takes: where to fetch it
     * from. {@link #carry} takes only from the container it goes to, and a room's bins fill in order, so the
     * nearest one may well be empty while another holds the lot (2026-09-25: the smokers' nearest Ore Bin
     * barrel was never the one the haul filled, and the crew walked to it for nothing, over and over).
     */
    @Nullable
    protected static BlockPos nearestWith(ServerLevel level, List<BlockPos> ps, BlockPos from, Predicate<ItemStack> pick) {
        List<BlockPos> holding = new ArrayList<>();
        for (BlockPos p : ps) {
            if (!Bins.peek(level, List.of(p), pick).isEmpty()) {
                holding.add(p);
            }
        }
        return nearest(holding, from);
    }

    /** Nearest of these to {@code from}. */
    @Nullable
    protected static BlockPos nearest(List<BlockPos> ps, BlockPos from) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : ps) {
            double d = p.distSqr(from);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }
}
