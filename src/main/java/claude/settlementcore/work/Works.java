package claude.settlementcore.work;

import claude.settlementcore.Nbt;
import claude.settlementcore.unit.SettlerMob;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.CoreConfig;
import claude.settlementcore.room.PlanPlacement;
import claude.settlementcore.room.RaiseJob;
import claude.settlementcore.room.RoomShoring;
import claude.settlementcore.room.Siting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The warren at work (user, 2026-09-25: "we need some survival friendly stuff, so furnace chambers, a
 * mine, and what else?"): every work room the clan has dug (Furnace Chambers, Ore Mine, Clan Hoard,
 * Slave Fields, Warlock's Workshop, Fungus Grove, Beast Pens) and the crews that run them. The Heart
 * keeps one of these and ticks it.
 *
 * <p><b>Crews.</b> A room's crew are slaves out of the horde (the Workshop's Warlock Engineer is
 * Skryre's loan), real rats who come up out of the room's floor while the room is loaded and go back
 * into the tunnels when it is not; like the teeming horde they are never saved, and whatever the Heart
 * cannot find again is back in the horde. A killed one is gone. With no slaves in the horde a room goes
 * unworked.
 *
 * <p><b>The haul.</b> Every few seconds the horde carries each room's work on through the tunnels
 * ({@link #haul}): the mine's ore to the Furnace Chambers' Ore Bin (coal to the Fuel Bin), the smelt, the
 * timber and the stone to the Hoard, the food into the larder's containers (core/food/Larder: the Heart
 * Chamber's Larder barrels, then the Hoard; the Beast Pens' fodder and the cooks' Pantry topped up first, raw
 * meat to the smokers while the clan is fed), and the Heart's own spoils to the Hoard. Without a Hoard, everything
 * but food waits in its room's bins.
 *
 * <p><b>The night.</b> A Warlord who sleeps in the Warlord's Den wakes to the night's work done
 * ({@link #nightsWork}): every room does what its crew would have done in the hours skipped.
 */
public final class Works {

    /**
     * The larder is its barrels now (core/food/Larder, 2026-09-27), and they take all the food they have room for. A clan
     * with no Larder barrel or Hoard with room (a Heart with no Heart Chamber) has the rooms' food put straight into its
     * ration as food points, up to this much.
     */
    public static final int LARDER_KEEP = 160;
    /** Fodder the Beast Pens' bin is kept stocked with, of each kind. */
    static final int FODDER_KEEP = 32;
    /** Seeds kept back in the Harvest Bin to sow empty plots. */
    static final int SEED_KEEP = 16;
    static final int HAUL_EVERY = 5;
    /** How far out of a room's box its idle hands are called to it ({@link #enlist}): inside the 24 blocks a crew may stray before it's taken as lost. */
    public static final int ENLIST_RANGE = 20;
    /** The most of a room's crew that come up out of the reserve in one second (they rise one beside another). */
    public static final int RISE_PER_SECOND = 3;
    /** A crew lent from outside the reserve (Under-Empire's Warlock Engineer, its Packmaster): another comes this long after one is killed. */
    public static final long LOAN_AGAIN = 20 * 60 * 3;
    /**
     * The last of a plan's price line, for the bridge's printout and a race's plan item: a site called off is torn down by its builders and its price
     * comes back; the earthworks (the ground cleared and levelled, and what grew on it) are never undone.
     */
    public static final String CALL_OFF_NOTE = "Called off, its builders tear down what they laid and the price comes back; the earthworks are never undone: "
            + "the ground cleared and levelled, and what grew on it, do not come back";

    private final Settlement heart;
    private final Map<BlockPos, RoomWork> rooms = new LinkedHashMap<>();
    /** Buildings being raised on the surface, by door (docs/plans/surface-builder.md): kept in {@link #rooms} too, while they stand. */
    private final Map<BlockPos, SiteWork> sites = new LinkedHashMap<>();
    /** Rooms being dug out ({@link #dig}), by door: their diggers' room until each is dug and handed over. */
    private final Map<BlockPos, DigWork> digs = new LinkedHashMap<>();
    private final Set<BlockPos> opened = new HashSet<>();
    private final Map<UUID, BlockPos> crew = new LinkedHashMap<>();
    /**
     * The price of sites a load could not read ({@link #load}: a plan that is broken, a room that is not registered any more, a second
     * site at one door), still to be paid back: the settlement's currency and the owner are not at hand while the Works load, so it is paid
     * at the first {@link #sync}. Saved, and only while it is not 0, so the Works' tag of a settlement with none is as it always was.
     */
    private int refundDue;
    /**
     * What the owner was told about their buildings while they were away (a site came down, or a load paid one back, with the owner off the server):
     * the gold is paid back at once and only the line would have been lost. Told at the first second they are about ({@link #tellOwed}); identical lines are one with a count, and
     * "X is raised." never pushes out a refund line ({@link OwedLines}). Saved, and only while any is owed, so the Works' tag of a settlement with none is as it always was.
     */
    private final OwedLines owed = new OwedLines();
    private final Map<BlockPos, Integer> here = new HashMap<>();
    private final Map<BlockPos, Long> lostAt = new HashMap<>();
    private CompoundTag saved = new CompoundTag();
    /** The owner's orders to the workshop (and the counts a race keeps with them). */
    private final WorkOrders orders = new WorkOrders();
    /** The race's own works beside the engine's (Under-Empire: nest/work/ClanUpkeep). */
    private final WorksExtension extension;

    public Works(Settlement heart) {
        this(heart, WorksExtension.NONE);
    }

    public Works(Settlement heart, WorksExtension extension) {
        this.heart = heart;
        this.extension = extension;
    }

    public WorkOrders orders() {
        return orders;
    }

    @Nullable
    static RoomWork make(Settlement heart, Building b) {
        Rooms.Room room = Rooms.get(b.room());
        if (room == null || b.door() == null || b.facing() == null) {
            return null;
        }
        PlanPlacement pl = PlanPlacement.compute(room, b.door(), b.facing());
        RoomWorks.Factory make = RoomWorks.get(room.function());
        return make == null ? null : make.make(heart, b, pl);
    }

    /** Crew out of the reserve's soldiers (a Watch-Post's watch): they go back as soldiers, not workers. */
    private final Set<UUID> clanratCrew = new HashSet<>();
    /**
     * Crew taken out of the reserve ({@link #spawnCrew}): they go back into it wherever they end, by where they came from and not by
     * the room they are the crew of by then (a site's builders handed over into a room with a crew lent from outside the reserve).
     */
    private final Set<UUID> fromReserve = new HashSet<>();

    /** A crew settler back into the reserve as what it was. */
    private void refund(UUID id) {
        fromReserve.remove(id);
        boolean clanrat = clanratCrew.remove(id);
        heart.addToReserve(clanrat ? 0 : 1, clanrat ? 1 : 0);
    }

    /**
     * Whether crew settler {@code id}, leaving the crew of {@code w} (null: the room is gone), goes back into the reserve: a horde
     * room's crew do, and so does anyone who came out of the reserve, whatever room they are the crew of now. A lent crew (the
     * Workshop's Warlock Engineer) goes back to where it was lent from.
     */
    private boolean toReserve(UUID id, @Nullable RoomWork w) {
        return w == null || w.crewFromHorde() || fromReserve.contains(id);
    }

    /** A room's crew (a post's watch), up and about. */
    public List<SettlerMob> crewOf(ServerLevel level, BlockPos room) {
        List<SettlerMob> out = new ArrayList<>();
        for (var e : crew.entrySet()) {
            if (e.getValue().equals(room) && level.getEntity(e.getKey()) instanceof SettlerMob r && r.isAlive()) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * A line to the owner about a building (the raise's: called off, down and paid back, a site a load could not read, raised): said at once when they are
     * about, else kept ({@link #owed}) and said when they next are, so a refund that came while they were off the server is not a refund nobody heard of.
     * What was kept comes first, so a line made on the second they come back (a load paying a site back) is not said before the older ones.
     * {@code routine}: good news with no gold in it ("is raised"), the first a full queue lets go. A settlement with no owner says nothing, as {@code tellOwner} does.
     */
    private void tell(String line, net.minecraft.ChatFormatting color, boolean routine) {
        if (heart.ownerPlayer() != null || heart.owner() == null) {
            tellOwed();
            heart.tellOwner(line, color);
            return;
        }
        owed.add(line, color, routine);
        heart.setChanged();
    }

    /** {@link #tell(String, net.minecraft.ChatFormatting, boolean)} of a line that matters. */
    private void tell(String line, net.minecraft.ChatFormatting color) {
        tell(line, color, false);
    }

    /** The lines kept for an owner who was away, said now that they are about (once a second: the first after they come back, with the settlement loaded), oldest first. */
    private void tellOwed() {
        if (owed.isEmpty() || heart.ownerPlayer() == null) {
            return;
        }
        for (OwedLines.Line l : owed.take()) {
            heart.tellOwner(l.said(), l.color());
        }
        heart.setChanged();
    }

    /** How many lines are kept for an owner who is away (the tests). */
    public int owedLines() {
        return owed.size();
    }

    /** The lines kept for an owner who is away, oldest first (the tests). */
    public List<OwedLines.Line> owedLineList() {
        return owed.lines();
    }

    /** The rooms' works, kept in step with the Heart's buildings (and its Watch-Posts). */
    void sync(ServerLevel level) {
        payBackUnreadSites();
        Set<BlockPos> live = new HashSet<>();
        for (var e : extension.extraRooms(this).entrySet()) {
            BlockPos key = e.getKey();
            RoomWork w = rooms.get(key);
            if (w == null) {
                w = e.getValue().get();
                String k = Long.toString(key.asLong());
                if (saved.contains(k)) {
                    w.load(saved.getCompoundOrEmpty(k));
                }
                rooms.put(key, w);
            }
            live.add(key);
        }
        for (SiteWork s : sites.values()) {                      // a building being raised: its builders' room until it is handed over
            rooms.putIfAbsent(s.key(), s);
            live.add(s.key());
        }
        for (DigWork d : digs.values()) {                        // a room being dug: its diggers' room until it is handed over
            rooms.putIfAbsent(d.key(), d);
            live.add(d.key());
        }
        for (Building b : heart.buildings()) {
            if (b.door() == null) {
                continue;
            }
            RoomWork w = rooms.get(b.door());
            if (w == null) {
                w = make(heart, b);
                if (w == null) {
                    continue;
                }
                String k = Long.toString(b.door().asLong());
                if (saved.contains(k)) {
                    w.load(saved.getCompoundOrEmpty(k));
                }
                rooms.put(b.door(), w);
            }
            live.add(b.door());
        }
        for (Iterator<Map.Entry<BlockPos, RoomWork>> it = rooms.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!live.contains(e.getKey())) {
                it.remove();                                     // the room is gone (the Heart was carried to a new warren)
                dismiss(level, e.getKey());
            }
        }
    }

    public List<RoomWork> rooms() {
        return new ArrayList<>(rooms.values());
    }

    @Nullable
    public RoomWork roomOf(SettlerMob rat) {
        return rat.crewRoom() == null ? null : rooms.get(rat.crewRoom());
    }

    public boolean has(Class<? extends RoomWork> kind) {
        return rooms.values().stream().anyMatch(kind::isInstance);
    }

    @SuppressWarnings("unchecked")
    public <W extends RoomWork> List<W> all(Class<W> kind) {
        List<W> out = new ArrayList<>();
        for (RoomWork w : rooms.values()) {
            if (kind.isInstance(w)) {
                out.add((W) w);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ ticking

    /** Every tick (the bellows), and a tick more now and then for a settlement whose rooms work faster (Settlement#workPace). */
    public void tick(ServerLevel level) {
        tickDue += heart.workPace() - 1f;
        int extra = (int) tickDue;
        tickDue -= extra;
        for (RoomWork w : rooms.values()) {
            int n = here.getOrDefault(w.key(), 0);
            for (int i = 0; n > 0 && i <= extra; i++) {
                w.tick(level, n);
            }
        }
    }

    /** The part of a tick, and of a second, a faster settlement's rooms are owed (Settlement#workPace). */
    private float tickDue, secondDue;

    /** Once a second: crews up or down to what each loaded room wants, each room's own work, the haul. */
    public void second(ServerLevel level, long sec) {
        secondDue += heart.workPace() - 1f;
        int extra = (int) secondDue;                             // a faster settlement's rooms: a second more now and then
        secondDue -= extra;
        sync(level);
        tellOwed();
        // crews the Heart can no longer find are back in the tunnels (never saved: a chunk unloaded, the world closed)
        Map<BlockPos, List<SettlerMob>> up = new HashMap<>();
        for (Iterator<Map.Entry<UUID, BlockPos>> it = crew.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Entity ent = level.getEntity(e.getKey());
            RoomWork home = rooms.get(e.getValue());
            boolean strayed = ent != null && home != null && !home.box().inflatedBy(24).isInside(ent.blockPosition())
                    && !home.reachInside(ent.blockPosition());
            if (ent instanceof SettlerMob r && r.isAlive() && r.isCrew() && home != null && !strayed) {
                up.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(r);
            } else if (home == null && ent instanceof SettlerMob r && r.isAlive() && (r.isHomeward() || r.leaveSpot() != null)) {
                // on its way down from a building raised into a room with no work (handOver), or walking off the building first (sendHome): the
                // settlement takes it back into the reserve when it gets there (forget), or here, if it is lost on the way (no entity: the branch below)
            } else {
                if (ent instanceof SettlerMob r && r.isAlive()) {
                    r.discard();
                }
                if (toReserve(e.getKey(), rooms.get(e.getValue()))) {
                    refund(e.getKey());
                }
                letGo(e.getValue(), e.getKey());                 // a site's builder: its batch goes back at once
                it.remove();
            }
        }
        here.clear();
        enlisted.keySet().retainAll(crew.keySet());              // gone from the crew (sent home, lost, its room gone): no longer on its way
        for (RoomWork w : rooms.values()) {
            BlockPos c = w.box().getCenter();
            boolean live = level.isLoaded(c) && level.isPositionEntityTicking(c);
            List<SettlerMob> mine = up.getOrDefault(w.key(), List.of());
            if (!live) {
                continue;
            }
            if (opened.add(w.key())) {
                w.opened(level);
            }
            int want = w.crewWanted();
            if (mine.size() < want) {
                staff(level, w, want - mine.size());
            } else if (mine.stream().filter(r -> !r.isHomeward() && r.leaveSpot() == null).count() > want + 1) {
                SettlerMob r = surplus(mine);
                if (r != null) {
                    sendHome(level, w.building, r);              // no work for it: back down to the horde (off a building first)
                }
            }
            if (!enlisted.isEmpty()) {
                hurryEnlisted(level, w, mine);
            }
            here.put(w.key(), mine.size());
            for (int i = 0; i <= extra; i++) {
                w.second(level, mine.size());
            }
        }
        finishSites(level);
        finishDigs(level);
        if (sec % HAUL_EVERY == 0) {
            haul(level);
        }
        extension.second(this, level);
    }

    // ------------------------------------------------------------------ gear: the workshop's orders

    /**
     * What the rooms want made for them in the workshop, over what the owner ordered (RoomWork#gear: the Throne Hall's
     * halberds), and what the race's own upkeep wants (WorksExtension#gear): of each, the most any asks, and why.
     */
    public Map<Crafter.Key, RoomWork.Gear> gear(ServerLevel level) {
        Map<Crafter.Key, RoomWork.Gear> out = new LinkedHashMap<>();
        if (!CoreConfig.roomsOrderGear.getAsBoolean()) {
            return out;
        }
        for (RoomWork w : rooms.values()) {
            for (RoomWork.Gear g : w.gear(level)) {
                if (g.keep() > 0) {
                    out.merge(Crafter.Key.of(g.item()), g, (a, b) -> a.keep() >= b.keep() ? a : b);
                }
            }
        }
        extension.gear(this, level, out);
        return out;
    }

    /** The ledger's Orders page: every order and where it stands (as the race shows it, or the engine's plain page). */
    public WorkOrders.View view(ServerLevel level) {
        sync(level);
        WorkOrders.View v = extension.view(this, level);
        return v != null ? v : new WorkOrders.View(new ArrayList<>(), 0, orders.slingers(), 0, orders.teams(), "", "");
    }

    /** The race's own works beside the engine's. */
    public WorksExtension extension() {
        return extension;
    }

    /** How many of {@code like} the clan holds: the Clan Hoard, the rooms' named bins (the Armoury...), the Heart's spoils. */
    public int holds(ServerLevel level, ItemStack like) {
        java.util.function.Predicate<ItemStack> same = s -> ItemStack.isSameItemSameComponents(s, like);
        int n = 0;
        for (HoardWork h : all(HoardWork.class)) {
            n += Bins.count(level, h.barrels(level), same);
        }
        for (RoomWork w : rooms.values()) {
            if (w instanceof HoardWork) {
                continue;
            }
            List<BlockPos> named = new ArrayList<>();
            for (BlockPos p : Bins.containers(level, w.box())) {
                if (!Bins.nameOf(level, p).isEmpty()) {
                    named.add(p);                                // a room's bin, not a chest of the Warlord's own
                }
            }
            n += Bins.count(level, named, same);
        }
        for (ItemStack s : heart.spoils()) {
            if (same.test(s)) {
                n += s.getCount();
            }
        }
        return n;
    }

    /**
     * Which of a room's crew goes home when it has more than it wants (the trim: one rat a second): one that is not on its way already, and one with
     * nothing to do before one at work. Passing over a rat already homeward makes each second's pick a new rat, so the crew is cut one a second and not
     * at the pace of one rat's sinking (24 ticks, against the second's 20); an idle rat goes first, so a builder holding a batch is not the one taken off
     * it. This was written when {@code goHome} began a sinking rat's descent over each time it was told: the last of the crew, told every second, never
     * got down, and the crew was cut one rat in 20 s, with a site's surplus builders standing idle on its stairs for minutes (the step platform's flake:
     * the last cell held 400 ticks by a builder that an idle one blocked from the way up). {@code goHome} returns at once for a rat homeward and sinking
     * now (9e0c399), so the choice is for the pace and the order only. Null if every rat is on its way home.
     */
    @Nullable
    static SettlerMob surplus(List<SettlerMob> crew) {
        SettlerMob busy = null;
        for (int i = crew.size() - 1; i >= 0; i--) {
            SettlerMob r = crew.get(i);
            if (r.isHomeward() || r.leaveSpot() != null) {
                continue;                                        // on its way already: the next one is told, one a second
            }
            if (r.crewDoing().isEmpty()) {
                return r;
            }
            if (busy == null) {
                busy = r;
            }
        }
        return busy;
    }

    /** How long, in ticks, a settler sent home from a building has to walk off it before it sinks where it stands. */
    static final int LEAVE_TICKS = 240;

    /**
     * A crew settler sent home. From a building on the surface it walks off first (the detail session's note on the film of 2026-10-01: builders sank
     * through the roof they stood on and read as vanishing): to the building's landing, on natural ground outside its box, and sinks there ({@link
     * SettlerMob#leaveVia}); it sinks at once from anywhere that isn't the building's top or inside it (a dug room's crew sink where they are, in the
     * tunnels), and where it stands if it has not got off in {@value #LEAVE_TICKS} ticks. The settlement takes it back all the same.
     */
    private void sendHome(ServerLevel level, @Nullable Building b, SettlerMob r) {
        if (r.leaveSpot() == null && b != null && Rooms.isRaised(b.room()) && b.box().inflatedBy(1).isInside(r.blockPosition())) {
            BlockPos spot = landing(level, b);
            if (spot != null) {
                r.leaveVia(spot, level.getGameTime() + LEAVE_TICKS);
                return;
            }
        }
        if (r.leaveSpot() == null) {
            r.goHome();
        }
    }

    /** The cell outside a raised building, 2 out from its door on the way in (where its builders come up), that a settler can stand in; null if none. */
    @Nullable
    private static BlockPos landing(ServerLevel level, Building b) {
        if (b.door() == null || b.facing() == null) {
            return null;
        }
        BlockPos at = b.door().relative(b.facing().getOpposite(), 2);
        for (int r = 0; r <= 3; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    for (int dy : new int[]{0, 1, -1, 2, -2, 3, -3}) {
                        BlockPos p = at.offset(dx, dy, dz);
                        if (!b.box().isInside(p) && level.isLoaded(p) && SettlerMob.standable(level, p)) {
                            return p;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * A room short of {@code need} crew: its settlement's idle hands nearby walk over to it first ({@link #enlist}), then the rest come up out of the
     * reserve, up to {@value #RISE_PER_SECOND} a second. Before 0.5.2 only the reserve gave a crew, one a second: a camp whose Grots were all up and
     * idle left a new site waiting for the next spawning round, and a crew of eight took eight seconds to gather (the user, 2026-11-01: "how come
     * it takes grots some time to start trying to build"). An enlisted settler is crew as any other: lost or killed at work, it is refunded into the
     * reserve (a buff the reserve's crew always had).
     */
    private void staff(ServerLevel level, RoomWork w, int need) {
        if (w.crewFromHorde()) {
            need -= enlist(level, w, need);
        }
        for (int i = 0; i < Math.min(need, RISE_PER_SECOND); i++) {
            if (!spawnCrew(level, w)) {
                return;
            }
        }
    }

    /**
     * Up to {@code need} of the settlement's people up and about that have nothing to do (at work with no room, no foe, not on their way home) and
     * are of the room's crew type, within {@value #ENLIST_RANGE} blocks of it, nearest first: off the roll and into the crew. They walk to it from
     * where they stand, and end as any crew out of the reserve does (back into it). Returns how many came.
     */
    int enlist(ServerLevel level, RoomWork w, int need) {
        if (need <= 0) {
            return 0;
        }
        BoundingBox near = w.box().inflatedBy(ENLIST_RANGE);
        BlockPos mid = w.box().getCenter();
        List<SettlerMob> idle = new ArrayList<>();
        for (SettlerMob r : heart.loadedPeople()) {
            if (r.getType() == w.crewType() && idle(r) && near.isInside(r.blockPosition())) {
                idle.add(r);
            }
        }
        idle.sort(java.util.Comparator.comparingDouble(r -> r.blockPosition().distSqr(mid)));
        boolean clanrat = w.crewType() == heart.soldierType();
        int n = 0;
        for (SettlerMob r : idle) {
            if (n >= need) {
                break;
            }
            if (!heart.takeFromRoll(r.getUUID())) {
                continue;
            }
            r.makeCrew(w.key());
            w.crewSpawned(r);
            crew.put(r.getUUID(), w.key());
            fromReserve.add(r.getUUID());                        // it ends as a crew out of the reserve does: back into it
            enlisted.put(r.getUUID(), level.getGameTime());
            if (clanrat) {
                clanratCrew.add(r.getUUID());
            }
            n++;
        }
        if (n > 0) {
            heart.setChanged();
        }
        return n;
    }

    /**
     * Enlisted crew still on their way, by when they were enlisted: one that hasn't reached its room in {@value #ENLIST_PATIENCE} ticks (behind a
     * wall, across a ditch, lost on a long walk) burrows to the room's way in, as crew come up there out of the reserve (the review of 4275dca:
     * a path can't tell, vanilla's always ends somewhere, beside the wall). Not saved: crew never are.
     */
    private final Map<UUID, Long> enlisted = new HashMap<>();
    /** Ticks an enlisted settler has to walk to its room before it burrows there. */
    public static final int ENLIST_PATIENCE = 300;

    /** Each second: an enlisted settler at its room is there; one still off it after {@value #ENLIST_PATIENCE} ticks burrows to the way in. */
    private void hurryEnlisted(ServerLevel level, RoomWork w, List<SettlerMob> mine) {
        for (SettlerMob r : mine) {
            Long at = enlisted.get(r.getUUID());
            if (at == null) {
                continue;
            }
            if (r.isHomeward() || r.leaveSpot() != null || w.box().inflatedBy(2).isInside(r.blockPosition()) || w.reachInside(r.blockPosition())) {
                enlisted.remove(r.getUUID());                    // there, or sent off again already
            } else if (level.getGameTime() - at >= ENLIST_PATIENCE && !r.isBurrowing()) {
                BlockPos way = w.comeUpAt(level);
                if (way != null) {                               // no way in yet: asked again next second
                    r.burrowTo(way.getX() + 0.5, way.getY(), way.getZ() + 0.5);
                    enlisted.remove(r.getUUID());
                }
            }
        }
    }

    /**
     * One of the settlement's people with nothing to do: at work and in no room's crew, with no foe, not burrowing or on its way home, and not busy
     * with its race's own business ({@link SettlerMob#isBusy}).
     */
    static boolean idle(SettlerMob r) {
        return r.isAlive() && !r.isCrew() && r.getMode() == SettlerMob.Mode.WORK && r.getTarget() == null && !r.isBurrowing() && !r.isHomeward()
                && r.leaveSpot() == null && !r.isBusy();
    }

    /** One of a room's crew up out of the reserve (or lent from outside it) at the room's way in: false if none could come. */
    private boolean spawnCrew(ServerLevel level, RoomWork w) {
        boolean clanrat = w.crewType() == heart.soldierType();
        if (w.crewFromHorde()) {
            if ((clanrat ? heart.reserveSoldiers() : heart.reserveWorkers()) <= 0) {
                return false;
            }
        } else if (level.getGameTime() < lostAt.getOrDefault(w.key(), Long.MIN_VALUE / 2) + LOAN_AGAIN) {
            return false;
        }
        BlockPos at = w.comeUpAt(level);                        // Works.standable for every room but a site (its landing)
        if (at == null) {
            return false;
        }
        SettlerMob rat = w.crewType().create(level, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        if (rat == null) {
            return false;
        }
        if (w.crewFromHorde() && !heart.takeFromReserve(clanrat)) {
            return false;
        }
        rat.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
        rat.bindTo(heart);
        rat.finalizeSpawn(level, level.getCurrentDifficultyAt(at), EntitySpawnReason.MOB_SUMMONED, null);
        rat.makeCrew(w.key());
        w.crewSpawned(rat);
        level.addFreshEntity(rat);
        crew.put(rat.getUUID(), w.key());
        if (w.crewFromHorde()) {
            fromReserve.add(rat.getUUID());
            if (clanrat) {
                clanratCrew.add(rat.getUUID());
            }
        }
        rat.emergeAt(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        heart.setChanged();
        if (!w.crewFromHorde()) {
            return false;                                        // a lent crew comes one at a time
        }
        return true;
    }

    /** A cell a crew rat can come up in, inside the room, near its middle if it can. */
    @Nullable
    static BlockPos standable(ServerLevel level, RoomWork w) {
        BoundingBox b = w.box();
        BlockPos c = b.getCenter();
        int floor = w.building.standingY();                      // the doorway's level first: not down a winze or up in a gallery
        for (int t = 0; t < 40; t++) {
            int r = 2 + t / 4;
            int x = c.getX() + level.getRandom().nextInt(2 * r + 1) - r;
            int z = c.getZ() + level.getRandom().nextInt(2 * r + 1) - r;
            for (int dy = 0; dy <= 1; dy++) {                      // the doorway's level: never the bottom of a pit it cannot climb out of
                for (int y : new int[]{floor + dy, floor - dy}) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (b.isInside(p) && SettlerMob.standable(level, p)) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    /** One of the crew was killed. */
    public void crewDied(UUID id, long now) {
        clanratCrew.remove(id);
        fromReserve.remove(id);
        BlockPos k = crew.remove(id);
        if (k != null) {
            letGo(k, id);
            RoomWork w = rooms.get(k);
            if (w != null && !w.crewFromHorde()) {
                lostAt.put(k, now);
            }
            heart.setChanged();
        }
    }

    /** One of the crew went back down into the tunnels by itself (goHome): the Heart's absorb() refunds it. */
    public boolean forget(UUID id) {
        BlockPos k = crew.remove(id);
        if (k == null) {
            return false;
        }
        letGo(k, id);
        if (!toReserve(id, rooms.get(k))) {
            return true;                                         // the Warlock goes back to Skryre, not into the horde
        }
        refund(id);
        return true;
    }

    public boolean isCrew(UUID id) {
        return crew.containsKey(id);
    }

    public int crewCount() {
        return crew.size();
    }

    public List<UUID> crewIds() {
        return new ArrayList<>(crew.keySet());
    }

    /** Every crew rat back into the tunnels at once (the Heart is carried off, or a room is gone). */
    public void recall(ServerLevel level) {
        for (UUID id : new ArrayList<>(crew.keySet())) {
            BlockPos k = crew.remove(id);
            if (k != null) {
                letGo(k, id);
            }
            if (level.getEntity(id) instanceof SettlerMob r) {
                r.discard();
            }
            if (toReserve(id, k == null ? null : rooms.get(k))) {
                refund(id);
            }
        }
        here.clear();
    }

    /** The room at door {@code key} is gone (Settlement#removeBuilding): its work dropped, its crew sent back into the reserve. */
    public void forgetRoom(ServerLevel level, BlockPos key) {
        if (rooms.get(key) instanceof SiteWork) {
            return;                                              // a site is called off, never forgotten
        }
        dismiss(level, key);
        rooms.remove(key);
        opened.remove(key);
        here.remove(key);
        lostAt.remove(key);                                      // a new room at the door waits out no loan of this one's
        saved.remove(Long.toString(key.asLong()));
        heart.setChanged();
    }

    private void dismiss(ServerLevel level, BlockPos room) {
        for (Iterator<Map.Entry<UUID, BlockPos>> it = crew.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (e.getValue().equals(room)) {
                letGo(room, e.getKey());
                if (level.getEntity(e.getKey()) instanceof SettlerMob r) {
                    r.discard();
                }
                refund(e.getKey());
                it.remove();
            }
        }
    }

    // ------------------------------------------------------------------ raising buildings (docs/plans/surface-builder.md)

    /** The buildings being raised, in the order they were begun. */
    public List<SiteWork> sites() {
        return new ArrayList<>(sites.values());
    }

    /**
     * The site {@code rat} is crew of, or null (not crew, or crew of a room that is raised): its id's door, then the sites by door,
     * two lookups and no scan (the fall subscriber asks this of every crew settler that lands, SiteWork.SureFooting).
     */
    @Nullable
    public SiteWork siteOf(SettlerMob rat) {
        BlockPos door = crew.get(rat.getUUID());
        return door == null ? null : sites.get(door);
    }

    /** Whether crew settler {@code id} came out of the reserve, and goes back into it wherever it ends (the tests). */
    public boolean cameFromReserve(UUID id) {
        return fromReserve.contains(id);
    }

    /** A builder of a site, or a digger of a dig, is gone: its batch goes back. */
    private void letGo(BlockPos room, UUID id) {
        SiteWork s = sites.get(room);
        if (s != null) {
            s.let(id);
        }
        DigWork d = digs.get(room);
        if (d != null) {
            d.let(id);
        }
    }

    /** The rooms being dug out. */
    public List<DigWork> digs() {
        return new ArrayList<>(digs.values());
    }

    /**
     * Begins digging the room at {@code pl} (its door the cell aimed at, a wall of the space it opens off; it runs away from there): its
     * door must be in the settlement, and the cell before it open, a hall or passage of the settlement's or the open air, so its diggers
     * come in from space they can stand in. Then its price is paid and its diggers come up ({@link DigWork}). Returns why it can't be, in
     * words for the player, or null when it is begun; every refusal comes before anything is spent.
     *
     * @param free paid for by no one: a creative dig, and the tests. Otherwise the room's cost, in the settlement's currency
     */
    @Nullable
    public String dig(ServerLevel level, PlanPlacement pl, boolean free) {
        Rooms.Room room = pl.room();
        String the = Settlement.the(room.displayName());
        if (Rooms.isRaised(room.id())) {
            return cap(the) + " is raised, not dug";
        }
        BlockPos door = pl.door();
        String at = door.getX() + " " + door.getY() + " " + door.getZ();
        if (digs.containsKey(door) || sites.containsKey(door) || rooms.containsKey(door)) {
            return "Something is being dug or raised at " + at + " already, or a room has its door there";
        }
        if (!heart.inSettlement(door)) {
            return cap(the) + "'s door at " + at + " is outside the settlement: dig it near its " + heart.centreName();
        }
        BlockPos before = door.relative(pl.facing().getOpposite());
        if (level.getBlockState(door).getCollisionShape(level, door).isEmpty()) {
            return "Aim the plan at a wall: " + the + " is dug into the rock, its door cut where you aim";
        }
        if (!level.getBlockState(before).getCollisionShape(level, before).isEmpty()
                || !level.getBlockState(before.above()).getCollisionShape(level, before.above()).isEmpty()) {
            return "Aim the plan at a wall you stand before: " + the + " is dug from there, its door where you aim";
        }
        for (DigWork d : digs.values()) {
            if (d.box().intersects(pl.box())) {
                return cap(the) + " would run into " + Settlement.the(d.room().displayName()) + ", still being dug";
            }
        }
        for (SiteWork s : sites.values()) {
            if (s.box().intersects(pl.box())) {
                return cap(the) + " would run into " + Settlement.the(s.room().displayName()) + ", still being raised";
            }
        }
        int cost = free ? 0 : room.cost();
        if (cost > 0 && !heart.spendCurrency(cost, "Digging " + the)) {
            return cap(the) + " costs " + heart.currencyAmount(cost) + ", and it has " + heart.currency();
        }
        claude.settlementcore.room.DigJob job = new claude.settlementcore.room.DigJob(room.id(), door, pl.facing());
        DigWork d = new DigWork(heart, job);
        job.ensureLoaded(level);
        digs.put(door, d);
        rooms.put(door, d);
        heart.setChanged();
        return null;
    }

    /** Digs whose every cell is done, handed over ({@link #handOverDig}). */
    private void finishDigs(ServerLevel level) {
        if (digs.isEmpty()) {
            return;
        }
        for (Iterator<DigWork> it = digs.values().iterator(); it.hasNext(); ) {
            DigWork d = it.next();
            if (d.dug() && level.isLoaded(d.box().getCenter())) {
                it.remove();
                handOverDig(level, d);
            }
        }
    }

    /**
     * A dug room becomes one of the settlement's, as a raised one does ({@link #handOver}): its fences and bars joined up, added (walled
     * where its dig walled up the holes beside it as it went), the dig dropped and the room's own work made at its door, its diggers the
     * new room's crew when it takes workers from the reserve, else sent home.
     */
    private void handOverDig(ServerLevel level, DigWork d) {
        BlockPos key = d.key();
        d.job().finishAll(level);
        d.job().joinUp(level);
        heart.addBuilding(d.building, d.job().walled());
        rooms.remove(key);
        opened.remove(key);
        saved.remove(Long.toString(key.asLong()));
        RoomWork next = make(heart, d.building);
        if (next != null) {
            rooms.put(key, next);
        }
        if (next == null || !next.crewFromHorde() || next.crewType() != d.crewType()) {
            for (SettlerMob r : crewOf(level, key)) {
                sendHome(level, d.building, r);
            }
        }
        tell(cap(Settlement.the(d.room().displayName())) + " is dug out.", net.minecraft.ChatFormatting.GREEN, true);
        heart.setChanged();
    }

    /** {@link #raise(ServerLevel, PlanPlacement, boolean, boolean)}, the land round it reshaped into ramps. */
    @Nullable
    public String raise(ServerLevel level, PlanPlacement pl, boolean free) {
        return raise(level, pl, free, true);
    }

    /**
     * Begins raising the building at {@code pl} ({@code PlanPlacement.onGround}): the land is sited ({@link Siting#plan}, the
     * other sites kept out of it as the settlement's rooms are), then its price is paid, then its builders come up. Returns
     * why it can't be, in words for the player, or null when it is begun. Every refusal comes before anything is spent.
     *
     * @param free    paid for by no one: a creative raise, and the tests. Otherwise the room's cost, in the settlement's currency
     * @param reshape false for the plinth, berm and landing only (the land round them is left as it is)
     */
    @Nullable
    public String raise(ServerLevel level, PlanPlacement pl, boolean free, boolean reshape) {
        Rooms.Room room = pl.room();
        String the = Settlement.the(room.displayName());
        if (!Rooms.isRaised(room.id())) {
            return cap(the) + " is dug, not raised";
        }
        BlockPos door = pl.door();
        String at = door.getX() + " " + door.getY() + " " + door.getZ();
        if (sites.containsKey(door)) {
            return "Something is being raised at " + at + " already";
        }
        if (!heart.inSettlement(door) && !heart.insideWalls(door) && !besideSite(door)) {
            return cap(the) + "'s door at " + at + " is outside the settlement: raise it near its " + heart.centreName() + ", or beside what it has built";
        }
        Siting.Result sited = Siting.plan(level, pl, withSites(), reshape);
        if (sited instanceof Siting.Refusal no) {
            return no.why();
        }
        Siting.Plan plan = (Siting.Plan) sited;
        int cost = free ? 0 : room.cost();
        if (cost > 0 && !heart.spendCurrency(cost, "Raising " + the)) {
            return cap(the) + " costs " + heart.currencyAmount(cost) + ", and it has " + heart.currency();
        }
        RaiseJob job = new RaiseJob(room.id(), door, pl.facing(), plan, cost);
        job.ensureLoaded(level);
        SiteWork site = new SiteWork(heart, job);
        sites.put(door, site);
        rooms.put(door, site);
        heart.setChanged();
        return null;
    }

    /**
     * Begins raising a wall of {@code style} (docs/plans/walls.md, E2) through {@code posts} (a {@code ring} closes back to the first), the posts in
     * {@code gatePosts} each the middle of a gate: the line is laid out on the ground as it is ({@link claude.settlementcore.room.WallJob#line}), then its
     * price is paid (the style's cost a column), then its builders come up, as for any building. Returns why it can't be, in words for the player,
     * or null when it is begun. Every refusal comes before anything is spent.
     */
    @Nullable
    public String raiseWall(ServerLevel level, List<BlockPos> posts, claude.settlementcore.room.WallStyle style, boolean ring,
                            java.util.Collection<Integer> gatePosts, boolean free) {
        if (Rooms.get(style.roomId()) == null) {
            return "No wall of the style " + style.id() + " is known";
        }
        claude.settlementcore.room.WallJob.Line line = claude.settlementcore.room.WallJob.line(level, heart.getBlockPos(), posts, ring, gatePosts, style);
        if (line.why() != null) {
            return line.why();
        }
        String the = Settlement.the(style.displayName());
        BlockPos door = BlockPos.of(line.feet()[0]);
        if (sites.containsKey(door)) {
            return "Something is being raised at " + door.getX() + " " + door.getY() + " " + door.getZ() + " already";
        }
        int cost = free ? 0 : (int) Math.min(Integer.MAX_VALUE, (long) style.cost() * line.feet().length);
        if (cost > 0 && !heart.spendCurrency(cost, "Raising " + the)) {
            return cap(the) + " costs " + heart.currencyAmount(cost) + " (" + line.feet().length + " columns), and it has " + heart.currency();
        }
        claude.settlementcore.room.WallJob job = new claude.settlementcore.room.WallJob(style, line, cost);
        job.ensureLoaded(level);
        SiteWork site = new SiteWork(heart, job);
        sites.put(door, site);
        rooms.put(door, site);
        heart.setChanged();
        return null;
    }

    /**
     * Begins patching the wall of {@code style} along {@code line} (the settlement's record of it, Walls#missing): only its {@code cells}, laid again by
     * its builders, free. Returns why it can't be, or null when it is begun.
     */
    @Nullable
    public String patchWall(ServerLevel level, claude.settlementcore.room.WallStyle style, claude.settlementcore.room.WallJob.Line line, long[] cells) {
        if (cells.length == 0 || Rooms.get(style.roomId()) == null) {
            return "Nothing to patch";
        }
        claude.settlementcore.room.WallJob job = claude.settlementcore.room.WallJob.patch(style, line, cells);
        BlockPos door = job.door;
        if (sites.containsKey(door)) {
            return "Something is being raised at " + door.getX() + " " + door.getY() + " " + door.getZ() + " already";
        }
        job.ensureLoaded(level);
        SiteWork site = new SiteWork(heart, job);
        sites.put(door, site);
        rooms.put(door, site);
        heart.setChanged();
        return null;
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Within 2 of a building being raised: a site may be begun there too (a temple's courts beside it). */
    private boolean besideSite(BlockPos p) {
        for (SiteWork s : sites.values()) {
            if (s.box().inflatedBy(2).isInside(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * What {@link #cancel} did (the owner's line, the bridge's printout, the tests): the site's room, what it cost, what its price comes to if every laid cell
     * that still holds its block comes down ({@code refund}: the whole price, less the share of the laid cells a player has changed), how many cells are to
     * come down and how many were laid, how many builders are at it, what the site was at, whether there was nothing to take down (the price was paid back at
     * once and the site is gone), and whether it was coming down already.
     */
    public record Cancelled(String room, int paid, int refund, int toTear, int laid, int builders, String state, boolean atOnce, boolean already) {
    }

    /**
     * Calls off the building being raised at {@code door} (surface-builder.md, section 9, "Stopping and undoing": the bridge's {@code raise cancel}, and a
     * race's plan item later), and its builders tear down what they laid: every cell the job finished that still holds the block it laid comes down, top
     * first (the reverse of the laying: hanging pieces before their ceilings), each with its dust and sound, a batch at a time by the crew, or, with no
     * builder to come (an empty reserve) or none taking a cell down for a while, by the site itself at a few cells a second ({@link SiteWork}). A player's
     * block in a cell, or a cell changed since, stays; the cells at the floor or under it become the land's own ground, not air (a torn-down site leaves
     * level ground), and the ones above it air. The earthworks stay, as they must (the land can't be un-reshaped: {@link #CALL_OFF_NOTE}). The site
     * stays among the {@link #sites()} until it is down; then ({@link #finishTeardown}) the price is paid back in the settlement's currency, once: all of
     * it, less the share of the cells that stayed ({@link RaiseJob#refundNow}); its builders go home and are taken back into the reserve once each; the
     * floor layer is level ground ({@link RaiseJob#levelFloor}); the owner is told, now and then; and a settlement with no sites saves no {@code sites} list again. A site mid-teardown is saved ({@code tearing},
     * {@code torn}) and goes on after a load. Null if no site is raised at {@code door}, or it is built and about to be handed over; a site already
     * coming down answers again ({@code already}) and is left to it.
     *
     * <p>SLICE 2 HOOK (section 10, "Never left behind"): strike the scaffold at once here, at the clearing's pace, before the building comes down:
     * Scaffold does not exist yet. SLICE 3 HOOK (section 7): a block laid from the Hoard goes back to the Hoard as it comes down ({@code RaiseJob#tearCell}).
     */
    @Nullable
    public Cancelled cancel(ServerLevel level, BlockPos door) {
        SiteWork s = sites.get(door);
        if (s == null || s.raised()) {
            return null;
        }
        RaiseJob job = s.job();
        job.ensureLoaded(level);
        boolean already = job.tearing();
        String the = Settlement.the(s.room().displayName());
        if (!already) {
            for (var e : crew.entrySet()) {
                if (e.getValue().equals(door)) {
                    letGo(door, e.getKey());                     // each builder's laying batch goes back: its next chore is the teardown's
                }
            }
            job.startTearDown(level, level.getGameTime());
        }
        boolean atOnce = job.tearOpen() == 0;
        Cancelled out = new Cancelled(s.room().displayName(), job.paid(), job.refundIfCalledOff(level), job.tearTotal(), job.laidCount(), crewOf(level, door).size(),
                job.state(), atOnce, already);
        if (!already) {
            if (atOnce) {
                sites.remove(door);
                finishTeardown(level, s);
            } else {
                tell(cap(the) + " is called off: your builders are pulling it down, and its price will come back. The earthworks stay.",
                        net.minecraft.ChatFormatting.YELLOW);
            }
            heart.setChanged();
        }
        return out;
    }

    /**
     * A site called off is down: its price is paid back, once ({@link RaiseJob#refundNow}), its builders go home (each is taken back into the reserve once,
     * when it gets there: {@link #forget}; the site is no room any more), and the owner is told. The caller has taken it out of {@link #sites}.
     */
    private void finishTeardown(ServerLevel level, SiteWork s) {
        RaiseJob job = s.job();
        BlockPos door = s.key();
        int paid = job.paid();
        int refund = job.refundNow();
        String the = Settlement.the(s.room().displayName());
        List<SettlerMob> builders = crewOf(level, door);
        job.levelFloor(level);                                   // no hole in the floor layer where it was never laid: level ground
        rooms.remove(door);
        opened.remove(door);
        here.remove(door);
        saved.remove(Long.toString(door.asLong()));
        for (SettlerMob r : builders) {
            sendHome(level, s.building, r);                      // off the site first, then sinks; Works#second, with no room for them, waits for it
        }
        if (refund > 0) {
            heart.addCurrency(refund, "Raising " + the + " (pulled down)");
        }
        tell(cap(the) + " is down" + (paid > 0 ? ": " + refund + " of the " + paid + " it cost " + (refund == 1 ? "is" : "are") + " paid back"
                + (refund < paid ? " (the rest stays with what was left standing)" : "") : "") + ". The earthworks stay.", net.minecraft.ChatFormatting.YELLOW);
        heart.setChanged();
    }

    /**
     * The price of the sites a load could not read, paid back at the first {@link #sync} (the level and the currency are at hand then, and
     * not in {@link #load}); the owner is told. Never again: it is saved only while it is owed.
     */
    private void payBackUnreadSites() {
        if (refundDue <= 0) {
            return;
        }
        int n = refundDue;
        refundDue = 0;
        heart.addCurrency(n, "a building that could not be read (paid back)");
        tell("A building being raised could not be read when the world loaded, so its price, " + n + " of the settlement's currency, is paid back. "
                + "What was built of it stands.", net.minecraft.ChatFormatting.YELLOW);
        heart.setChanged();
    }

    /** The currency still owed for sites a load could not read (the tests). */
    public int refundDue() {
        return refundDue;
    }

    /** The settlement for siting a new building: its own rooms and heart, and every cell the sites being raised work. */
    private RoomShoring.Warren withSites() {
        return new RoomShoring.Warren() {
            @Override
            public it.unimi.dsi.fastutil.longs.LongSet roomCells(ServerLevel level, BoundingBox area, BoundingBox except) {
                it.unimi.dsi.fastutil.longs.LongOpenHashSet out = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(heart.roomCells(level, area, except));
                for (DigWork d : digs.values()) {                // a room being dug: its cells are its, as a finished room's are
                    if (d.box().equals(except) || !d.box().intersects(area)) {
                        continue;
                    }
                    for (claude.settlementcore.room.RoomTemplates.Entry e : claude.settlementcore.room.RoomTemplates.get(level, d.room().id())) {
                        BlockPos w = d.job().placement().world(e.local());
                        if (area.isInside(w)) {
                            out.add(w.asLong());
                        }
                    }
                }
                for (SiteWork s : sites.values()) {
                    RaiseJob job = s.job();
                    job.ensureLoaded(level);
                    BoundingBox f = job.footprint();
                    if (s.box().equals(except) || f == null || !f.intersects(area)) {
                        continue;
                    }
                    for (long c : job.cells()) {
                        if (area.isInside(BlockPos.getX(c), BlockPos.getY(c), BlockPos.getZ(c))) {
                            out.add(c);
                        }
                    }
                }
                return out;
            }

            @Override
            public boolean shored(long pos) {
                return heart.shored(pos);
            }

            @Override
            public void shore(long pos) {
                heart.shore(pos);
            }

            @Override
            public void unshore(long pos) {
                heart.unshore(pos);
            }
        };
    }

    /** Sites whose every cell is done, handed over (see {@link #handOver}), and sites called off that are down, paid back ({@link #finishTeardown}). */
    private void finishSites(ServerLevel level) {
        if (sites.isEmpty()) {
            return;
        }
        for (Iterator<SiteWork> it = sites.values().iterator(); it.hasNext(); ) {
            SiteWork s = it.next();
            if (s.raised() && level.isLoaded(s.box().getCenter())) {
                it.remove();
                handOver(level, s);
            } else if (s.job().tornDown() && level.isLoaded(s.box().getCenter())) {
                it.remove();                                     // called off, and down
                finishTeardown(level, s);
            }
        }
    }

    /**
     * A raised building becomes one of the settlement's (surface-builder.md, section 2): its fences and stairs joined up, then
     * added walled (never walled up against the sky: the Heart's wallUpRooms walls any building added unwalled). The site is
     * dropped and its key cleared from {@link #opened} and {@link #saved}, and the room's own work made at the same door, which
     * the next second opens (its {@code opened()} runs once, as any room's). The builders are keyed to that door, so they are
     * the room's crew now. A room with no work (none registered for its kind, or none made for it), or whose crew is lent from
     * outside the reserve (the Workshop's Warlock Engineer: {@code crewFromHorde()} false: slaves are not its crew, and while they
     * filled it his own would never come up), or whose crew is another kind than the builders' (a soldier room, a barracks: its
     * clanrats would never come up while slaves filled the crew), sends them home, and each is taken back into the reserve when it
     * gets there (#forget; {@code toReserve}: by where it came from, not by the room it was the crew of), once.
     */
    private void handOver(ServerLevel level, SiteWork s) {
        BlockPos key = s.key();
        s.job().finishAll(level);                                // nothing left to put in; and its joinUp
        claude.settlementcore.room.WallJob wall = s.job() instanceof claude.settlementcore.room.WallJob w ? w : null;
        boolean patch = wall != null && wall.patch();            // a wall's patch is no building of its own: its builders go home
        if (!patch) {
            heart.addBuilding(s.building, true);
        }
        rooms.remove(key);
        opened.remove(key);
        saved.remove(Long.toString(key.asLong()));
        RoomWork next = patch ? null : make(heart, s.building);
        if (next != null) {
            rooms.put(key, next);
        }
        if (next == null || !next.crewFromHorde() || next.crewType() != s.crewType()) {
            for (SettlerMob r : crewOf(level, key)) {
                sendHome(level, s.building, r);
            }
        }
        if (wall != null) {
            heart.wallChanged(level.getGameTime());              // the walls are scanned again: the ring it closed is found (docs/plans/walls.md)
            if (!patch) {
                heart.wallRaised(wall);
            }
        }
        tell(cap(Settlement.the(s.room().displayName())) + (patch ? " is patched." : " is raised."), net.minecraft.ChatFormatting.GREEN, true);
        heart.setChanged();
    }

    /** The ground the rooms' work reaches beyond their walls (the Ore Mine's drifts): part of the warren too. */
    public List<BoundingBox> reach() {
        List<BoundingBox> out = new ArrayList<>();
        for (RoomWork w : rooms.values()) {
            if (w instanceof MineWork m) {
                out.addAll(m.reach());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the haul

    /** Food a thing is worth to the larder: its nutrition (grain counts 2, a cake 14; clan/Fare). */
    public static int ration(ItemStack s) {
        return claude.settlementcore.food.Fare.nutrition(s);
    }

    private static boolean fodder(ItemStack s) {
        return s.is(Items.WHEAT) || s.is(Items.WHEAT_SEEDS) || s.is(Items.CARROT) || s.is(Items.POTATO) || s.is(Items.BEETROOT)
                || s.is(Items.BEETROOT_SEEDS);
    }

    /** Whatever plants a crop: seeds, carrots, potatoes, Pam's HarvestCraft's produce. */
    private static boolean seed(ItemStack s) {
        return s.getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() instanceof net.minecraft.world.level.block.CropBlock;
    }

    private static boolean meat(ItemStack s) {
        return s.is(net.neoforged.neoforge.common.Tags.Items.FOODS_RAW_MEAT) || s.is(net.neoforged.neoforge.common.Tags.Items.FOODS_RAW_FISH);
    }

    /**
     * What the cooks want in the Pantry: whatever the kitchen's recipes use (KitchenBook: grain, vegetables,
     * fruit, meat raw and cooked, eggs, sugar, milk, mushrooms, any mod's; nothing better than cooked fare).
     */
    private static boolean pantryFood(ServerLevel level, @org.jetbrains.annotations.Nullable CookWork cook, ItemStack s) {
        return cook != null && cook.wants(level, s);
    }

    /** Into the Hoard (any of them); what did not fit is returned. */
    private ItemStack toHoard(ServerLevel level, ItemStack s) {
        ItemStack rest = s;
        for (HoardWork h : all(HoardWork.class)) {
            if (rest.isEmpty()) {
                break;
            }
            rest = h.put(level, rest);
        }
        return rest;
    }

    /** Move what {@code dest} takes of the live stack {@code s}: the stack shrinks by what went. */
    private static void move(ItemStack s, java.util.function.Function<ItemStack, ItemStack> dest) {
        if (s.isEmpty()) {
            return;
        }
        ItemStack rest = dest.apply(s.copy());
        s.shrink(s.getCount() - rest.getCount());
    }

    /** The haul log's name for a room's place. */
    private static String name(@Nullable RoomWork w) {
        return w == null ? "" : w.room().displayName();          // "" is never logged: a room the warren does not have
    }

    /** {@link #move}, with the road it went along logged (nest/ledger/HaulLog). */
    private void move(ItemStack s, String from, String to, java.util.function.Function<ItemStack, ItemStack> dest) {
        if (s.isEmpty()) {
            return;
        }
        net.minecraft.world.item.Item item = s.getItem();
        int was = s.getCount();
        move(s, dest);
        heart.log(from, to, item, was - s.getCount());
    }

    /** {@link #topUp}, with the road it went along logged. */
    private void topUp(ServerLevel level, List<BlockPos> into, ItemStack s, int keep, String from, String to) {
        if (s.isEmpty()) {
            return;
        }
        net.minecraft.world.item.Item item = s.getItem();
        int was = s.getCount();
        topUp(level, into, s, keep);
        heart.log(from, to, item, was - s.getCount());
    }

    /** Top the containers up to {@code keep} of this kind from the live stack. */
    private static void topUp(ServerLevel level, List<BlockPos> into, ItemStack s, int keep) {
        if (s.isEmpty() || into.isEmpty()) {
            return;
        }
        int have = Bins.count(level, into, x -> ItemStack.isSameItemSameComponents(x, s));
        int give = Math.min(s.getCount(), keep - have);
        if (give > 0) {
            ItemStack rest = Bins.insert(level, into, s.copyWithCount(give));
            s.shrink(give - rest.getCount());
        }
    }

    /** Bowls left from stews the larder took, to go back to the cooks. */
    private int bowls;

    /**
     * Food from the live stack into the larder's containers as it is (core/food/Larder: the Heart Chamber's barrels, then the
     * Hoard), where the clan eats it and the Warlord can take it; {@code from}: for the haul log. It used to be turned into
     * food points up to {@link #LARDER_KEEP}, and what went in was gone for good (the user, 2026-09-27).
     */
    private void feed(ServerLevel level, ItemStack s, String from) {
        int r = ration(s);
        int grade = claude.settlementcore.food.Fare.grade(s);
        if (s.isEmpty() || grade < 0 || r <= 0) {
            return;
        }
        s.shrink(heart.stockLarder(level, s, from));
        // no Larder barrel or Hoard with room (a Heart with no Heart Chamber round it): into the ration as food points, up
        // to LARDER_KEEP, as before the larder was barrels
        net.minecraft.world.item.Item item = s.getItem();
        int was = s.getCount();
        while (!s.isEmpty() && heart.food() < LARDER_KEEP) {
            if (claude.settlementcore.food.Fare.leftover(s).is(Items.BOWL)) {
                bowls++;
            }
            heart.addFood(heart.foodValue(r), grade, from);
            s.shrink(1);
        }
        heart.log(from, claude.settlementcore.ledger.HaulLog.LARDER, item, was - s.getCount());
    }

    /** Below this the larder takes raw food at once, rather than leave it for the cooks. */
    static final int LARDER_LOW = 40;
    /** Of each ingredient, what the Cook-Pits' Pantry is kept stocked with. */
    public static final int PANTRY_KEEP = 32;

    /**
     * The horde carries each room's work on through the tunnels (see the class notes). The fare decides the
     * food's road: what the cooks and the smokers made goes to the larder first; raw food goes to the cooks
     * and the smokers while there are any, and into the larder raw only when it runs low.
     */
    public void haul(ServerLevel level) {
        boolean hoard = has(HoardWork.class);
        ForgeWork forge = all(ForgeWork.class).stream().findFirst().orElse(null);
        CookWork cook = all(CookWork.class).stream().findFirst().orElse(null);
        boolean smokers = forge != null && forge.has(level, RecipeType.SMOKING);
        boolean rawToLarder = !(smokers || cook != null) || heart.food() < LARDER_LOW;
        List<BlockPos> pantry = cook == null ? List.of() : cook.pantry(level);
        List<BlockPos> touched = new ArrayList<>(pantry);
        bowls = 0;
        // the Heart's own spoils
        if (hoard && !heart.spoils().isEmpty()) {
            for (Iterator<ItemStack> it = heart.spoils().iterator(); it.hasNext(); ) {
                ItemStack s = it.next();
                move(s, heart.spoilsName(), heart.hoardName(), st -> toHoard(level, st));
                if (s.isEmpty()) {
                    it.remove();
                }
            }
            heart.setChanged();
        }
        // what the cooks made: the larder first, then the Hoard
        if (cook != null) {
            List<BlockPos> made = cook.fareBins(level);
            touched.addAll(made);
            for (ItemStack s : Bins.stacks(level, made)) {
                feed(level, s, name(cook));
                if (hoard) {
                    move(s, name(cook), heart.hoardName(), st -> toHoard(level, st));
                }
            }
        }
        // the smelt: the smokers' food to the larder (and cooked meat to the Pantry for stew), the rest to the Hoard
        for (ForgeWork f : all(ForgeWork.class)) {
            touched.addAll(f.ingotBins(level));
            for (ItemStack s : Bins.stacks(level, f.ingotBins(level))) {
                if (claude.settlementcore.food.Fare.grade(s) >= 0) {
                    if (pantryFood(level, cook, s)) {
                        topUp(level, pantry, s, 16, name(f), name(cook));
                    }
                    feed(level, s, name(f));
                }
                if (hoard) {
                    move(s, name(f), heart.hoardName(), st -> toHoard(level, st));
                }
            }
        }
        // the mine's ore and stone
        for (MineWork m : all(MineWork.class)) {
            List<BlockPos> carts = m.carts(level);
            touched.addAll(carts);
            for (ItemStack s : Bins.stacks(level, carts)) {
                if (forge != null && ForgeWork.cooks(level, RecipeType.SMELTING, s)) {
                    move(s, name(m), name(forge), st -> Bins.insert(level, forge.oreBins(level), st));
                } else if (forge != null && level.fuelValues().isFuel(s)) {
                    move(s, name(m), name(forge), st -> Bins.insert(level, forge.fuelBins(level), st));
                }
                if (hoard) {
                    move(s, name(m), heart.hoardName(), st -> toHoard(level, st));
                }
            }
            if (hoard) {
                touched.addAll(m.stoneBins(level));
                for (ItemStack s : Bins.stacks(level, m.stoneBins(level))) {
                    move(s, name(m), heart.hoardName(), st -> toHoard(level, st));
                }
            }
        }
        // the harvest: fodder, the Pantry, the smokers, the larder, the Hoard (a few seeds kept back for sowing)
        List<PensWork> pens = all(PensWork.class);
        for (FieldsWork f : all(FieldsWork.class)) {
            List<BlockPos> bins = f.bins(level);
            touched.addAll(bins);
            for (ItemStack s : Bins.stacks(level, bins)) {
                if (fodder(s)) {
                    for (PensWork p : pens) {
                        topUp(level, p.fodderBins(level), s, FODDER_KEEP, name(f), name(p));
                    }
                }
                if (pantryFood(level, cook, s)) {
                    topUp(level, pantry, s, PANTRY_KEEP, name(f), name(cook));
                }
                if (smokers && !rawToLarder && ForgeWork.cooks(level, RecipeType.SMOKING, s)) {
                    move(s, name(f), name(forge), st -> Bins.insert(level, forge.oreBins(level), st));
                }
                if (rawToLarder) {
                    feed(level, s, name(f));
                }
            }
            if (hoard) {
                for (ItemStack s : Bins.stacks(level, bins)) {
                    int keep = seed(s) ? Math.max(0, SEED_KEEP - (Bins.count(level, bins, x -> ItemStack.isSameItem(x, s)) - s.getCount())) : 0;
                    if (s.getCount() > keep) {
                        ItemStack part = s.copyWithCount(s.getCount() - keep);
                        ItemStack rest = toHoard(level, part);
                        s.shrink(part.getCount() - rest.getCount());
                        heart.log(name(f), heart.hoardName(), part.getItem(), part.getCount() - rest.getCount());
                    }
                }
            }
        }
        // the butcher's work: meat to the Pantry and the smokers (the larder takes it raw only when short), eggs to
        // the Pantry for pies, the rest to the Hoard
        for (PensWork p : pens) {
            List<BlockPos> bins = p.butcherBins(level);
            touched.addAll(bins);
            for (ItemStack s : Bins.stacks(level, bins)) {
                if (pantryFood(level, cook, s)) {
                    topUp(level, pantry, s, s.is(Items.EGG) ? 16 : PANTRY_KEEP, name(p), name(cook));
                }
                if (smokers && !rawToLarder && meat(s)) {
                    move(s, name(p), name(forge), st -> Bins.insert(level, forge.oreBins(level), st));
                }
                if (rawToLarder) {
                    feed(level, s, name(p));
                }
                if (hoard) {
                    move(s, name(p), heart.hoardName(), st -> toHoard(level, st));
                }
            }
        }
        // the orchard's fruit and berries: the Pantry, the larder (raw: at once only while it runs low, if there is
        // cooking), the Hoard; saplings back into the Sapling Crate
        for (OrchardWork o : all(OrchardWork.class)) {
            List<BlockPos> bins = o.bins(level);
            touched.addAll(bins);
            touched.addAll(o.crates(level));
            for (ItemStack s : Bins.stacks(level, bins)) {
                if (OrchardWork.sapling(s)) {
                    move(s, st -> Bins.insert(level, o.crates(level), st));
                }
                if (pantryFood(level, cook, s)) {
                    topUp(level, pantry, s, PANTRY_KEEP, name(o), name(cook));
                }
                if (rawToLarder) {
                    feed(level, s, name(o));
                }
                if (hoard) {
                    move(s, name(o), heart.hoardName(), st -> toHoard(level, st));
                }
            }
        }
        // timber: a few logs to the Pantry for bowls, the rest to the Hoard
        for (GroveWork g : all(GroveWork.class)) {
            touched.addAll(g.bins(level));
            for (ItemStack s : Bins.stacks(level, g.bins(level))) {
                if (s.is(net.minecraft.tags.ItemTags.LOGS)) {
                    topUp(level, pantry, s, 8, name(g), name(cook));
                }
                if (hoard) {
                    move(s, name(g), heart.hoardName(), st -> toHoard(level, st));
                }
            }
        }
        // the buckets the cooks emptied (the milk for a cake), to the Beast Pens to be milked into again
        if (!pens.isEmpty() && !pantry.isEmpty()) {
            List<BlockPos> into = pens.get(0).fodderBins(level);
            int have = Bins.count(level, into, st -> st.is(Items.BUCKET));
            if (have < 4 && !into.isEmpty()) {
                ItemStack b = Bins.take(level, pantry, st -> st.is(Items.BUCKET), 4 - have);
                if (!b.isEmpty()) {
                    int was = b.getCount();
                    ItemStack rest = Bins.insert(level, into, b);
                    heart.log(name(cook), name(pens.get(0)), Items.BUCKET, was - rest.getCount());
                    if (!rest.isEmpty()) {
                        Bins.insert(level, pantry, rest);
                    }
                    touched.addAll(into);
                }
            }
        }
        // the bowls the stews came in, back to the cooks
        if (bowls > 0) {
            ItemStack b = new ItemStack(Items.BOWL, bowls);
            ItemStack rest = Bins.insert(level, pantry, b);
            if (cook != null) {
                heart.log(claude.settlementcore.ledger.HaulLog.LARDER, name(cook), Items.BOWL, bowls - rest.getCount());
            }
            if (hoard && !rest.isEmpty()) {
                int was = rest.getCount();
                heart.log(claude.settlementcore.ledger.HaulLog.LARDER, heart.hoardName(), Items.BOWL, was - toHoard(level, rest).getCount());
            }
            bowls = 0;
        }
        if (hoard) {
            supply(level, touched);
        }
        Bins.changed(level, touched);
        if (forge != null) {
            Bins.changed(level, forge.oreBins(level));
            Bins.changed(level, forge.fuelBins(level));
        }
    }

    /** The most the horde carries out of the Hoard to the rooms in one haul. */
    static final int SUPPLY_BUDGET = 512;

    /**
     * The Hoard's supply (user, 2026-09-26: "they dont grab the materials they need from the hoard"): every room's supply
     * bins kept stocked out of the Clan Hoard (RoomWork#supplies: the Forge's ore and fuel, the Fields' seed, the cooks'
     * makings, the Orchard's saplings, the Pens' fodder, the Throne Hall's halberds, the Fleshpits' trough), up to
     * {@link #SUPPLY_BUDGET} a haul. Only the Hoard's barrels are drawn on: never the Warlord's own chests, never another
     * room's bins.
     */
    private void supply(ServerLevel level, List<BlockPos> touched) {
        List<HoardWork> hoards = all(HoardWork.class);
        int budget = SUPPLY_BUDGET;
        for (RoomWork w : rooms.values()) {
            if (w instanceof HoardWork || budget <= 0 || !level.isLoaded(w.box().getCenter())) {
                continue;
            }
            for (RoomWork.Supply sp : w.supplies(level)) {
                if (sp.bins().isEmpty()) {
                    continue;
                }
                boolean full = false;
                for (HoardWork h : hoards) {
                    for (BlockPos p : h.barrels(level)) {
                        net.minecraft.world.Container c = Bins.at(level, p);
                        for (int i = 0; c != null && i < c.getContainerSize() && budget > 0 && !full; i++) {
                            final ItemStack st = c.getItem(i);
                            if (st.isEmpty() || !sp.what().test(st)) {
                                continue;
                            }
                            int have = Bins.count(level, sp.bins(), x -> ItemStack.isSameItemSameComponents(x, st));
                            int n = Math.min(Math.min(sp.keep() - have, st.getCount()), budget);
                            if (n <= 0) {
                                continue;
                            }
                            ItemStack rest = Bins.insert(level, sp.bins(), st.copyWithCount(n));
                            int went = n - rest.getCount();
                            if (went > 0) {
                                heart.log(heart.hoardName(), name(w), st.getItem(), went);
                                st.shrink(went);
                                if (st.isEmpty()) {
                                    c.setItem(i, ItemStack.EMPTY);
                                }
                                c.setChanged();
                                budget -= went;
                                touched.add(p);
                                touched.addAll(sp.bins());
                            }
                            full = !rest.isEmpty();                // its bins have no room for more
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ the night

    /** {@code seconds} of every room's work at once (the Warlord slept in the Den); then the haul carries it on. */
    public void nightsWork(ServerLevel level, int seconds, List<Component> report) {
        sync(level);
        for (RoomWork w : rooms.values()) {
            BlockPos c = w.box().getCenter();
            if (level.isLoaded(c)) {
                w.nightsWork(level, seconds, report);
            }
        }
        for (PensWork p : all(PensWork.class)) {
            p.sweep(level);
        }
        for (int i = 0; i < 4; i++) {
            haul(level);
        }
    }

    public void describe(ServerLevel level, List<Component> out) {
        for (RoomWork w : rooms.values()) {
            if (level.isLoaded(w.box().getCenter())) {
                w.describe(level, out);
            }
        }
    }

    // ------------------------------------------------------------------ save / load

    public CompoundTag save(net.minecraft.core.HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        t.put("orders", orders.save(registries));
        extension.save(t, registries);
        CompoundTag st = saved.copy();
        for (RoomWork w : rooms.values()) {
            if (w instanceof SiteWork || w instanceof DigWork) {
                continue;                                        // saved as its job, under "sites" or "digs"
            }
            st.put(Long.toString(w.key().asLong()), w.save());
        }
        t.put("rooms", st);
        if (refundDue > 0) {                                     // only while it is owed, like 'sites': a settlement with none saves as it did
            t.putInt("refundDue", refundDue);
        }
        if (!owed.isEmpty()) {                                   // likewise: lines an owner who was away is still to hear
            t.put("told", owed.save());
        }
        if (!sites.isEmpty()) {                                  // only then: a settlement with none saves as it did
            ListTag sl = new ListTag();
            for (SiteWork s : sites.values()) {
                sl.add(s.job().save());
            }
            t.put("sites", sl);
        }
        if (!digs.isEmpty()) {
            ListTag dl = new ListTag();
            for (DigWork d : digs.values()) {
                dl.add(d.job().save());
            }
            t.put("digs", dl);
        }
        ListTag cl = new ListTag();
        for (var e : crew.entrySet()) {
            CompoundTag c = new CompoundTag();
            Nbt.putUUID(c, "id", e.getKey());
            c.put("room", Nbt.pos(e.getValue()));
            if (clanratCrew.contains(e.getKey())) {
                c.putBoolean("clanrat", true);
            }
            RoomWork home = rooms.get(e.getValue());
            if (fromReserve.contains(e.getKey()) && (home == null || !home.crewFromHorde())) {
                c.putBoolean("reserve", true);                   // a site's builder on its way home from a lent crew's room: the room alone wouldn't send it back
            }
            cl.add(c);
        }
        t.put("crew", cl);                                       // the rats are never saved: on load they are back in the tunnels
        return t;
    }

    public void load(CompoundTag t, net.minecraft.core.HolderLookup.Provider registries) {
        orders.load(t.getCompoundOrEmpty("orders"), registries);
        extension.load(t, registries);
        saved = t.getCompoundOrEmpty("rooms");
        rooms.clear();
        sites.clear();
        digs.clear();
        for (Tag x : t.getListOrEmpty("digs")) {
            claude.settlementcore.room.DigJob job = claude.settlementcore.room.DigJob.load((CompoundTag) x);
            if (job != null && Rooms.get(job.roomId) != null && !digs.containsKey(job.door)) {
                digs.put(job.door, new DigWork(heart, job));    // its diggers come up again once it is loaded (sync)
            }
        }
        refundDue = Math.max(0, t.getIntOr("refundDue", 0));
        owed.load(t.getListOrEmpty("told"));
        for (Tag x : t.getListOrEmpty("sites")) {
            CompoundTag st = (CompoundTag) x;
            RaiseJob job = claude.settlementcore.room.WallJob.isWall(st) ? claude.settlementcore.room.WallJob.loadWall(st) : RaiseJob.load(st);
            if (job != null && !sites.containsKey(job.door)) {
                sites.put(job.door, new SiteWork(heart, job));  // its builders come up again once it is loaded (sync)
            } else {
                // dropped (a plan or room that can't be read, a second site at a door): the price it was paid goes back at the first sync,
                // not here, where there is no level and no currency yet; what was built of it stands
                refundDue = (int) Math.min(Integer.MAX_VALUE, (long) refundDue + Math.max(0, ((CompoundTag) x).getIntOr("paid", 0)));
            }
        }
        crew.clear();
        clanratCrew.clear();
        fromReserve.clear();
        for (Tag x : t.getListOrEmpty("crew")) {
            CompoundTag c = (CompoundTag) x;
            if (Nbt.hasUUID(c, "id")) {
                Nbt.readPos(c, "room").ifPresent(p -> {   // an entry with no room is no crew: it leaves no id in either set
                    crew.put(Nbt.getUUID(c, "id"), p);
                    if (c.getBooleanOr("clanrat", false)) {
                        clanratCrew.add(Nbt.getUUID(c, "id"));
                    }
                    if (c.getBooleanOr("reserve", false)) {
                        fromReserve.add(Nbt.getUUID(c, "id"));
                    }
                });
            }
        }
    }
}
