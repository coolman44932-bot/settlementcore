package claude.settlementcore.room;

import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.CoreConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Deep Warrens (user, 2026-09-25: "lower levels should be its own thing that is automatically made,
 * like for each room thats like a junction or resting place, there should be ladders or something that
 * goes deeper which in turn reveals more skaven"; the horde gnaws them, two levels, deeper if you want).
 *
 * <p>When a Warren Junction, Breeding Pens, Heart Chamber or Gnaw-Hole Shaft is finished (or is found
 * already finished, in a warren older than this), the horde plans a way down off it: the Way Down
 * (deep_hole), a Great spiral stair {@link RoomCatalog#DEEP_STEP} blocks deep, opened off a spare doorway
 * of the room or cut out through its wall, and at its foot a level of its own laid out from the deep
 * pieces (block bench's ue_deep.py): tunnels, forks and bends, sleeping caverns, refuse pits, gnawing
 * fronts and, in the Underdeep, the great Horde Pits. Each level has a hub, and when it is gnawed out the
 * next level down begins off it, down to config deepLevels (2: the Deep Warrens, then the Underdeep).
 *
 * <p>The horde gnaws it all, not the warband: a stretch of blocks a second in dig order from the top of
 * the Way Down outward ({@link #rate}, faster the bigger the horde), waiting at any chunk nobody has
 * loaded, and cutting only the ground itself (DigJob#natural), never anything a player built. The deeper
 * you go, the more of the horde teems about you (clan/Horde#swarmTarget, {@link #tierAt}).
 */
public final class DeepLevels {

    /** Blocks a second the horde gnaws: one per this many rats of the horde, within RATE_MIN..RATE_MAX. */
    public static final int HORDE_PER_BLOCK = 8;
    public static final int RATE_MIN = 30;
    public static final int RATE_MAX = 250;
    /** Pieces a level is laid out to (the Way Down not counted). */
    private static final int[] LEVEL_PIECES = {0, 9, 11};
    /** How far a level spreads from the foot of its Way Down (horizontally). */
    private static final int SPREAD = 56;
    /** Tests: blocks a second, whatever the horde (0: the horde decides). */
    public static int testRate;

    /** One level beneath a room: the Way Down first, then its pieces in the order they were laid out. */
    private static final class Level {
        final String source;
        final BoundingBox sourceBox;
        final int tier;
        final List<DigJob> jobs = new ArrayList<>();
        final List<String> kinds = new ArrayList<>();
        int done;
        boolean announced;

        Level(String source, BoundingBox sourceBox, int tier) {
            this.source = source;
            this.sourceBox = sourceBox;
            this.tier = tier;
        }

        boolean finished() {
            return done >= jobs.size();
        }
    }

    private final Settlement heart;
    private final List<Level> levels = new ArrayList<>();
    /** Rooms already given a way down (or found to have no room for one): room id @ box min. */
    private final Set<String> opened = new HashSet<>();
    private boolean scanned;
    @Nullable private String lastFailure;

    public DeepLevels(Settlement heart) {
        this.heart = heart;
    }

    /** The level {@code tier} down in the engine's words (a race's own: {@code heart.deepWords().level(tier)}). */
    public static String levelName(int tier) {
        return Settlement.DeepWords.ENGINE.level(tier);
    }

    /** The lines about digging down, in {@code w}'s words, for {@code room} ("the Warren Junction"): no way down, begun, cut into {@code where}, a level dug. */
    public static String noWayDown(Settlement.DeepWords w, String room) {
        return w.who() + " can find no way down beneath " + room + ": the ground round it is taken.";
    }

    public static String begunDown(Settlement.DeepWords w, String room, int tier) {
        return "Beneath " + room + ", " + w.starts() + " to " + w.level(tier) + ".";
    }

    public static String wayCutInto(Settlement.DeepWords w, String room, String where) {
        return w.whose() + " beneath " + room + " would have cut into " + (where.isEmpty() ? w.home() : where) + ": " + w.looks() + ".";
    }

    public static String levelDug(Settlement.DeepWords w, String room, int tier) {
        return capital(w.level(tier)) + " beneath " + room + (tier == 1 ? " are " + w.dug() + ". " + w.deeper() : " is " + w.dug() + ". " + w.deepest());
    }

    public static int maxLevels() {
        return CoreConfig.deepLevels.getAsInt();
    }

    private static String key(String room, BoundingBox box) {
        return room + "@" + box.minX() + "," + box.minY() + "," + box.minZ();
    }

    /** The level a room of the warren belongs to: 0 for the warren the Warlord digs, the deep pieces' own. */
    private static int tierOf(String room) {
        for (Rooms.Deep d : Rooms.deepPieces()) {
            if (d.id().equals(room)) {
                return d.tier();
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ what the rest of the mod asks

    /** Whether {@code p} is in a level gnawed (or being gnawed) beneath the warren. */
    public boolean contains(BlockPos p) {
        return tierAt(p) > 0;
    }

    /** The deepest level {@code p} is in (its pieces and its Way Down), or 0. */
    public int tierAt(BlockPos p) {
        int t = 0;
        for (Level l : levels) {
            if (l.tier <= t) {
                continue;
            }
            for (DigJob j : l.jobs) {
                if (j.placement().box().isInside(p)) {
                    t = l.tier;
                    break;
                }
            }
        }
        return t;
    }

    /** What the horde is gnawing right now, for the Heart's report: "the Deep Warrens beneath the Warren Junction, 43%", or null. */
    @Nullable
    public String progress() {
        for (Level l : levels) {
            if (!l.finished()) {
                long all = 0;
                long got = 0;
                for (DigJob j : l.jobs) {
                    all += 1;
                    got += j.isDone() ? 1 : 0;
                }
                return heart.deepWords().level(l.tier) + " beneath " + Settlement.theRoom(l.source) + ", "
                        + Math.round(100.0 * (l.done + (l.done < l.jobs.size() ? l.jobs.get(l.done).progress() : 0)) / l.jobs.size()) + "%";
            }
        }
        return null;
    }

    public int levelsOpen() {
        return (int) levels.stream().filter(Level::finished).count();
    }

    /** Tests and logs: every level, its pieces' kinds and how far it is gnawed. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Level l : levels) {
            sb.append("[tier ").append(l.tier).append(" off ").append(l.source).append(": ").append(l.done).append('/').append(l.jobs.size())
                    .append(' ').append(l.kinds).append(']');
        }
        return sb.append(" opened=").append(opened.size()).append(lastFailure == null ? "" : " last failure " + lastFailure).toString();
    }

    /** Every dig of every level, planned, under way or done. */
    public List<DigJob> allJobs() {
        List<DigJob> out = new ArrayList<>();
        for (Level l : levels) {
            out.addAll(l.jobs);
        }
        return out;
    }

    /** Tests: every dig of the levels at {@code tier} (each level's Way Down first). */
    public List<DigJob> jobsAt(int tier) {
        List<DigJob> out = new ArrayList<>();
        for (Level l : levels) {
            if (l.tier == tier) {
                out.addAll(l.jobs);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ planning a level

    /** A room is finished: if a way down opens off rooms like it, the horde plans the level below. */
    public void roomFinished(ServerLevel level, Building b) {
        if (!Rooms.deepSources().contains(b.room()) || opened.contains(key(b.room(), b.box()))) {
            return;
        }
        int tier = tierOf(b.room()) + 1;
        if (tier > maxLevels()) {
            return;
        }
        opened.add(key(b.room(), b.box()));
        heart.setChanged();
        PlanPlacement src = placementOf(level, b);
        if (src == null) {
            return;
        }
        Level planned = plan(level, b, src, tier);
        if (planned == null) {
            ServerPlayer p = heart.ownerPlayer();
            if (p != null) {
                p.sendSystemMessage(Component.literal(noWayDown(heart.deepWords(), Settlement.theRoom(b.room()))).withStyle(ChatFormatting.GRAY));
            }
            return;
        }
        levels.add(planned);
        heart.setChanged();
        ServerPlayer p = heart.ownerPlayer();
        if (p != null) {
            p.sendSystemMessage(Component.literal(begunDown(heart.deepWords(), Settlement.theRoom(b.room()), tier)).withStyle(ChatFormatting.DARK_GREEN));
        }
    }

    /**
     * Where a finished room stands and which way it was dug. Rooms finished before this was kept are
     * worked out from their box: of every placement of the template that gives that box, the one whose
     * open cells (a sample of the template's air) are most open in the world.
     */
    @Nullable
    public static PlanPlacement placementOf(ServerLevel level, Building b) {
        Rooms.Room room = Rooms.get(b.room());
        if (room == null) {
            return null;
        }
        if (b.door() != null && b.facing() != null) {
            return PlanPlacement.compute(room, b.door(), b.facing());
        }
        List<RoomTemplates.Entry> air = new ArrayList<>();
        List<RoomTemplates.Entry> all = RoomTemplates.get(level, b.room());
        for (int i = 0; i < all.size(); i += Math.max(1, all.size() / 400)) {
            if (all.get(i).state().isAir()) {
                air.add(all.get(i));
            }
        }
        PlanPlacement best = null;
        int bestScore = -1;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int cx = 0; cx < 2; cx++) {
                for (int cz = 0; cz < 2; cz++) {
                    BlockPos corner = new BlockPos(cx == 0 ? b.box().minX() : b.box().maxX(), b.box().minY(), cz == 0 ? b.box().minZ() : b.box().maxZ());
                    PlanPlacement probe = PlanPlacement.compute(room, BlockPos.ZERO, d);
                    BlockPos origin = corner.subtract(probe.box().getCenter().subtract(probe.origin()))
                            .offset(b.box().getCenter().subtract(corner));
                    PlanPlacement pl = new PlanPlacement(room, probe.rotation(), origin, shift(probe.box(), origin.subtract(probe.origin())),
                            origin.offset(probe.door().subtract(probe.origin())));
                    if (!pl.box().equals(b.box())) {
                        continue;
                    }
                    int score = 0;
                    for (RoomTemplates.Entry e : air) {
                        BlockPos w = pl.world(e.local());
                        if (level.isLoaded(w) && level.getBlockState(w).isAir()) {
                            score++;
                        }
                    }
                    if (score > bestScore) {
                        best = pl;
                        bestScore = score;
                    }
                }
            }
        }
        return best;
    }

    private static BoundingBox shift(BoundingBox b, BlockPos by) {
        return new BoundingBox(b.minX() + by.getX(), b.minY() + by.getY(), b.minZ() + by.getZ(),
                b.maxX() + by.getX(), b.maxY() + by.getY(), b.maxZ() + by.getZ());
    }

    /** The world spot and way of a template-local spot of a placed room. */
    private static Rooms.Spot world(PlanPlacement pl, Rooms.Spot s) {
        BlockPos w = pl.world(new BlockPos(s.x(), s.y(), s.z()));
        return new Rooms.Spot(w.getX(), w.getY(), w.getZ(), pl.rotation().rotate(s.facing()));
    }

    /** The Way Down off a spare spot of the room, and a level laid out from its foot; null if nothing fits. */
    @Nullable
    private Level plan(ServerLevel level, Building b, PlanPlacement src, int tier) {
        Rooms.Room hole = Rooms.get("deep_hole");
        List<BoundingBox> taken = new ArrayList<>();
        for (BoundingBox o : heart.settlementBoxes()) {           // its rooms and the digs begun (a room placed, not yet gnawed out)
            if (!o.equals(b.box())) {
                taken.add(o);
            }
        }
        for (Level l : levels) {
            for (DigJob j : l.jobs) {
                if (!j.placement().box().equals(b.box())) {
                    taken.add(j.placement().box());          // the room the way down opens off is not in its way
                }
            }
        }
        RandomSource rnd = RandomSource.create(heart.getBlockPos().asLong() * 31 + b.box().minX() * 7L + b.box().minZ() * 13L + tier);
        StringBuilder why = new StringBuilder();
        for (Rooms.Spot local : src.room().annexes()) {
            Rooms.Spot at = world(src, local);
            BlockPos start = new BlockPos(at.x(), at.y(), at.z());
            boolean doorway = src.room().exits().stream().anyMatch(e -> e.x() == local.x() && e.y() == local.y() && e.z() == local.z());
            if (doorway) {
                BlockPos beyond = start.relative(at.facing());
                if (taken.stream().anyMatch(t -> t.isInside(beyond))) {
                    why.append(at.facing()).append(":used ");
                    continue;                                    // that doorway already leads into another room or tunnel
                }
            }
            PlanPlacement way = PlanPlacement.compute(hole, start, at.facing());
            if (way.box().minY() <= level.getMinY() + 2 || clash(way.box(), taken, null)) {
                why.append(at.facing()).append(":clash ");
                for (BoundingBox t : taken) {
                    if (t.intersects(way.box())) {
                        why.append(t).append(' ');
                    }
                }
                continue;
            }
            Level l = new Level(b.room(), b.box(), tier);
            DigJob wayJob = job(way).naturalOnly(b.box());
            l.jobs.add(wayJob);
            l.kinds.add("hole");
            List<BoundingBox> mine = new ArrayList<>(taken);
            mine.add(way.box());
            Rooms.Spot exit = world(way, hole.exits().get(0));
            layOut(level, l, exit, mine, rnd);
            return l;
        }
        lastFailure = b.room() + ": " + why;
        return null;
    }

    private static boolean clash(BoundingBox box, List<BoundingBox> taken, @Nullable BoundingBox except) {
        for (BoundingBox t : taken) {
            if (!t.equals(except) && t.intersects(box)) {
                return true;
            }
        }
        return false;
    }

    private DigJob job(PlanPlacement pl) {
        DigJob j = new DigJob(pl.room().id(), pl.door(), facingOf(pl.rotation()));
        j.keepOutFrom(heart::keepOutCells);
        j.spoilsTo(heart::takeSpoils);
        j.walls(heart);
        return j;
    }

    private static Direction facingOf(Rotation r) {
        return switch (r) {
            case CLOCKWISE_90 -> Direction.SOUTH;
            case CLOCKWISE_180 -> Direction.WEST;
            case COUNTERCLOCKWISE_90 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    /**
     * Grow the level from the foot of its Way Down, breadth first: at every open mouth a piece of the
     * level's kind that fits (no piece of the warren, the deeps or this level in its box, within
     * {@link #SPREAD} of the foot), until the level has its pieces; then the mouths still open get a
     * gnawing front or a cave-in, if one fits (a mouth left open ends in the rock, still being gnawed).
     * The hub (where the level below begins) goes in as soon as it fits, a Horde Pit in the Underdeep too.
     */
    private void layOut(ServerLevel level, Level l, Rooms.Spot foot, List<BoundingBox> taken, RandomSource rnd) {
        int kindTier = Math.min(l.tier, 2);
        List<Rooms.Deep> pool = Rooms.deepPieces().stream().filter(d -> d.tier() == kindTier).toList();
        boolean wantHub = l.tier < maxLevels();
        boolean wantPit = kindTier == 2;
        int target = LEVEL_PIECES[Math.min(kindTier, LEVEL_PIECES.length - 1)];
        BlockPos footPos = new BlockPos(foot.x(), foot.y(), foot.z());
        Deque<Rooms.Spot> open = new ArrayDeque<>();
        open.add(foot);
        int placed = 0;
        while (!open.isEmpty()) {
            Rooms.Spot mouth = open.poll();
            BlockPos door = new BlockPos(mouth.x(), mouth.y(), mouth.z()).relative(mouth.facing());
            List<Rooms.Deep> tries = new ArrayList<>();
            if (placed < target) {
                if (wantHub && placed >= 2) {
                    pool.stream().filter(d -> d.kind().equals("hub")).findFirst().ifPresent(tries::add);
                }
                if (wantPit && placed >= 1) {
                    pool.stream().filter(d -> d.id().startsWith("horde_pit")).findFirst().ifPresent(tries::add);
                }
                for (int k = 0; k < 6; k++) {
                    Rooms.Deep d = pick(pool, rnd, placed < 3);
                    if (d != null) {
                        tries.add(d);
                    }
                }
            }
            pool.stream().filter(d -> d.kind().equals("end")).forEach(tries::add);   // last: close the mouth off
            for (Rooms.Deep d : tries) {
                Rooms.Room room = Rooms.get(d.id());
                PlanPlacement pl = PlanPlacement.compute(room, door, mouth.facing());
                BoundingBox box = pl.box();
                if (clash(box, taken, null) || box.minY() <= level.getMinY() + 2
                        || Math.abs(box.getCenter().getX() - footPos.getX()) > SPREAD || Math.abs(box.getCenter().getZ() - footPos.getZ()) > SPREAD) {
                    continue;
                }
                taken.add(box);
                l.jobs.add(job(pl).naturalOnly(null));
                l.kinds.add(d.kind());
                if (!d.kind().equals("end")) {
                    placed++;
                }
                if (d.kind().equals("hub")) {
                    wantHub = false;
                }
                if (d.id().startsWith("horde_pit")) {
                    wantPit = false;
                }
                Rooms.Spot reserved = d.kind().equals("hub") ? reserveWayDown(level, pl, room, taken) : null;
                for (Rooms.Spot e : room.exits()) {
                    if (e != reserved) {
                        open.add(world(pl, e));
                    }
                }
                break;
            }
        }
    }

    /**
     * A hub keeps one doorway, and the ground its Way Down will take, free of the rest of its level: the
     * level below opens there once the hub is gnawed out. Returns the doorway kept (template-local), or null.
     */
    @Nullable
    private static Rooms.Spot reserveWayDown(ServerLevel level, PlanPlacement hub, Rooms.Room room, List<BoundingBox> taken) {
        Rooms.Room hole = Rooms.get("deep_hole");
        for (Rooms.Spot e : room.exits()) {
            Rooms.Spot at = world(hub, e);
            PlanPlacement way = PlanPlacement.compute(hole, new BlockPos(at.x(), at.y(), at.z()), at.facing());
            if (!clash(way.box(), taken, hub.box()) && way.box().minY() > level.getMinY() + 2) {
                taken.add(way.box());
                return e;
            }
        }
        return null;
    }

    @Nullable
    private static Rooms.Deep pick(List<Rooms.Deep> pool, RandomSource rnd, boolean early) {
        int total = 0;
        for (Rooms.Deep d : pool) {
            if (usable(d, early)) {
                total += d.weight();
            }
        }
        if (total == 0) {
            return null;
        }
        int r = rnd.nextInt(total);
        for (Rooms.Deep d : pool) {
            if (usable(d, early) && (r -= d.weight()) < 0) {
                return d;
            }
        }
        return null;
    }

    /** Early on a level keeps spreading (no dead ends); the hub and the Horde Pit are placed on purpose. */
    private static boolean usable(Rooms.Deep d, boolean early) {
        return !d.kind().equals("hub") && !d.id().startsWith("horde_pit") && !(early && d.kind().equals("end"));
    }

    // ------------------------------------------------------------------ the horde at work

    /** Blocks a second the horde gnaws: the more rats in the tunnels, the faster. */
    public int rate() {
        return testRate > 0 ? testRate : Math.max(RATE_MIN, Math.min(RATE_MAX, heart.reserve() / HORDE_PER_BLOCK));
    }

    /** A second's gnawing, from the Heart. */
    public void tick(ServerLevel level) {
        if (!scanned) {
            scanned = true;
            // a warren older than the deep levels: its rooms get their ways down now
            for (Building b : new ArrayList<>(heart.buildings())) {
                roomFinished(level, b);
            }
        }
        if (heart.reserve() <= 0) {
            return;                                              // nobody in the tunnels to gnaw
        }
        for (Level l : levels) {
            if (l.finished()) {
                continue;
            }
            DigJob j = l.jobs.get(l.done);
            BoundingBox inTheWay = blocker(l, j);
            if (inTheWay != null) {
                giveWay(level, l, j, inTheWay);                  // (it changes the levels: nothing more this second)
                heart.setChanged();
                return;
            }
            List<BlockPos> dust = new ArrayList<>();
            j.gnawInOrder(level, rate(), dust);
            crumble(level, dust);
            if (j.isDone()) {
                j.joinUp(level);                                // its fences and bars joined, not left loose posts
                Building built = new Building(j.roomId, j.placement().box(), j.door, j.facing);
                heart.addBuilding(built, j.walled());
                l.done++;
                if (l.finished()) {
                    announce(level, l);
                }
                roomFinished(level, built);                      // a hub: the level below begins
            }
            heart.setChanged();
            return;                                              // one level at a time, the nearest first
        }
    }

    /**
     * A room of the warren in the way of {@code j}, one dug or begun since its level was planned (the plan kept clear of
     * everything there was then): the horde plans a Way Down when its room is finished but gnaws it slowly, and a room
     * the Warlord placed, or the Heart Chamber, in between was gnawed through (the user's world, 2026-09-27: "skaven
     * randomly decided to make deeper entrances right where a existing part of the warren is"; one cut the tunnel to
     * the Ore Mine and the mine itself, and the Furnace Chambers dug beside another lost its Fuel and Ore Bins to it).
     * The room it opens off, and the levels' own pieces, are not in its way.
     */
    @Nullable
    private BoundingBox blocker(Level l, DigJob j) {
        BoundingBox box = j.placement().box();
        java.util.Set<BoundingBox> ours = new HashSet<>();
        for (Level o : levels) {
            for (DigJob d : o.jobs) {
                ours.add(d.placement().box());
            }
        }
        for (BoundingBox t : heart.settlementBoxes()) {
            if (!t.equals(l.sourceBox) && !ours.contains(t) && t.intersects(box)) {
                return t;
            }
        }
        return null;
    }

    /**
     * {@code j} would cut into {@code room}: the Warlord's rooms come first. A Way Down not yet gnawed out gives way and
     * is planned again off its room, round what stands there now (what it had gnawed of the old one stays, a pit in the
     * rock). A piece of a level below: the level ends where it has got to.
     */
    private void giveWay(ServerLevel level, Level l, DigJob j, BoundingBox room) {
        BoundingBox box = j.placement().box();
        BlockPos meet = new BlockPos((Math.max(box.minX(), room.minX()) + Math.min(box.maxX(), room.maxX())) / 2,
                (Math.max(box.minY(), room.minY()) + Math.min(box.maxY(), room.maxY())) / 2,
                (Math.max(box.minZ(), room.minZ()) + Math.min(box.maxZ(), room.maxZ())) / 2);
        String where = heart.placeOf(meet);
        ServerPlayer p = heart.ownerPlayer();
        if (l.done > 0) {
            l.jobs.subList(l.done, l.jobs.size()).clear();
            l.kinds.subList(l.done, l.kinds.size()).clear();
            announce(level, l);
            return;
        }
        levels.remove(l);
        opened.remove(key(l.source, l.sourceBox));
        if (p != null) {
            p.sendSystemMessage(Component.literal(wayCutInto(heart.deepWords(), Settlement.theRoom(l.source), where)).withStyle(ChatFormatting.DARK_GREEN));
        }
        for (Building b : heart.buildings()) {
            if (b.room().equals(l.source) && b.box().equals(l.sourceBox)) {
                roomFinished(level, b);
                return;
            }
        }
    }

    /** Dust and the gnawing of a thousand teeth where the horde works, if anyone is near enough to hear it. */
    private static void crumble(ServerLevel level, List<BlockPos> dust) {
        if (dust.isEmpty()) {
            return;
        }
        BlockPos at = dust.get(0);
        if (level.getNearestPlayer(at.getX(), at.getY(), at.getZ(), 40, false) == null) {
            return;
        }
        for (BlockPos p : dust) {
            var st = level.getBlockState(p.below()).isAir() ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState() : level.getBlockState(p.below());
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, st), p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5, 6, 0.4, 0.4, 0.4, 0.1);
        }
        level.playSound(null, at, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.BLOCKS, 0.7f, 0.6f + level.getRandom().nextFloat() * 0.3f);
        if (level.getRandom().nextInt(3) == 0) {
            level.playSound(null, at, SoundEvents.SILVERFISH_AMBIENT, SoundSource.HOSTILE, 0.6f, 0.7f);
        }
    }

    private void announce(ServerLevel level, Level l) {
        if (l.announced) {
            return;
        }
        l.announced = true;
        ServerPlayer p = heart.ownerPlayer();
        if (p != null) {
            p.sendSystemMessage(Component.literal(levelDug(heart.deepWords(), Settlement.theRoom(l.source), l.tier)).withStyle(ChatFormatting.GREEN));
        }
        level.playSound(null, l.jobs.get(0).door, SoundEvents.BELL_RESONATE, SoundSource.BLOCKS, 1f, 0.5f);
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ saving

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        ListTag ls = new ListTag();
        for (Level l : levels) {
            CompoundTag c = new CompoundTag();
            c.putString("source", l.source);
            c.putIntArray("source_box", new int[]{l.sourceBox.minX(), l.sourceBox.minY(), l.sourceBox.minZ(),
                    l.sourceBox.maxX(), l.sourceBox.maxY(), l.sourceBox.maxZ()});
            c.putInt("tier", l.tier);
            c.putInt("done", l.done);
            c.putBoolean("announced", l.announced);
            ListTag jl = new ListTag();
            for (int i = 0; i < l.jobs.size(); i++) {
                CompoundTag jt = l.jobs.get(i).save();
                jt.putString("kind", l.kinds.get(i));
                jl.add(jt);
            }
            c.put("jobs", jl);
            ls.add(c);
        }
        t.put("levels", ls);
        ListTag ol = new ListTag();
        for (String k : opened) {
            ol.add(net.minecraft.nbt.StringTag.valueOf(k));
        }
        t.put("opened", ol);
        return t;
    }

    public void load(CompoundTag t) {
        levels.clear();
        opened.clear();
        scanned = false;
        for (Tag x : t.getListOrEmpty("levels")) {
            CompoundTag c = (CompoundTag) x;
            int[] b = c.getIntArray("source_box").orElse(new int[0]);
            if (b.length != 6) {
                continue;
            }
            Level l = new Level(c.getStringOr("source", ""), new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]), c.getIntOr("tier", 0));
            l.done = c.getIntOr("done", 0);
            l.announced = c.getBooleanOr("announced", false);
            for (Tag y : c.getListOrEmpty("jobs")) {
                CompoundTag jt = (CompoundTag) y;
                DigJob j = DigJob.load(jt);
                j.keepOutFrom(heart::keepOutCells);
                j.spoilsTo(heart::takeSpoils);
                j.walls(heart);
                l.jobs.add(j);
                l.kinds.add(jt.getStringOr("kind", ""));
            }
            levels.add(l);
        }
        for (Tag x : t.getListOrEmpty("opened")) {
            opened.add(x.asString().orElse(""));
        }
    }
}
