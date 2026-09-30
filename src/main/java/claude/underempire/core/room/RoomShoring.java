package claude.underempire.core.room;

import claude.underempire.core.settlement.Building;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The Skaven shore their warren (user, 2026-09-25: "there seems to be a lot of holes when skaven build somewhere with
 * no existing floor or a open cave"). A room's template holds its open space and the walls it builds, and leaves the
 * rest to the world's rock: the gnawed caves (the Heart Chamber, the deep levels, the Breeding Pens, the Warpstone
 * Mine, the Gnaw-Hole Shaft) are mostly rock-walled. Dug where the rock was not there (a cave, a drop, the open sky),
 * they came out holed. So as a dig carves each open cell, whatever outside the room beside it is open (air, the
 * cave's plants, water and lava, loose sand and gravel) is walled up with the room's own floor, wall or ceiling
 * blocks. Rock is left as it is. Left open: the room's doorways, the other rooms of the warren (built, being dug or
 * planned: they open into each other), and a tunnel into the room: an opening in its wall at floor level no bigger
 * than {@link #PASSAGE} cells across, with ground under it and no wider behind it (the Warlord's own way in, a narrow
 * cave passage; a gap on a ledge of a ragged wall with a cave behind it is a hole). Water,
 * lava and loose ground are walled up even there: the warren must not flood or cave in.
 *
 * <p>Where the warren walled up is kept ({@link Warren#shored}): a later dig of the warren cuts it as it cuts the rock,
 * even a dig that cuts only the ground (DigJob#naturalOnly). Rooms dug before the shoring get it once
 * ({@link #wallUp}), air and plants only.
 */
public final class RoomShoring {

    /** The most cells across an opening at floor level may be and still be a tunnel into the room (a 3x3 tunnel). */
    public static final int PASSAGE = 9;

    /** Which face of the room a hole is in: under its floor, over its ceiling, or in a wall. */
    private static final byte FLOOR = 0, CEILING = 1, WALL = 2;

    /** The warren a room is dug for: its other rooms, never walled up, and where it has walled up. */
    public interface Warren {
        /** The cells of the warren's other rooms (built, being dug or planned) in {@code area}, and its Heart. */
        LongSet roomCells(ServerLevel level, BoundingBox area, BoundingBox except);

        boolean shored(long pos);

        void shore(long pos);

        void unshore(long pos);

        /** A room on its own (tests, a dig with no Heart): nothing else about it, its walling kept here. */
        static Warren alone() {
            LongOpenHashSet walls = new LongOpenHashSet();
            return new Warren() {
                @Override
                public LongSet roomCells(ServerLevel level, BoundingBox area, BoundingBox except) {
                    return new LongOpenHashSet();
                }

                @Override
                public boolean shored(long pos) {
                    return walls.contains(pos);
                }

                @Override
                public void shore(long pos) {
                    walls.add(pos);
                }

                @Override
                public void unshore(long pos) {
                    walls.remove(pos);
                }
            };
        }
    }

    /** Cells outside the room beside its open space, and which face of the room each is in. */
    private final Long2ByteOpenHashMap holes = new Long2ByteOpenHashMap();
    /** Every cell of the room (and the cells it shares with the rooms it opens into). */
    private final LongOpenHashSet room = new LongOpenHashSet();
    /** The room's open cells, and its whole blocks, as its template has them (not as the dig has got to them yet). */
    private final LongOpenHashSet planOpen = new LongOpenHashSet();
    private final LongOpenHashSet planSolid = new LongOpenHashSet();
    /** The room's open cells, where the world has them (for the walling up of a room already dug). */
    private final List<BlockPos> open = new ArrayList<>();
    private final LongSet others;
    /** The room's own floor, ceiling and wall blocks, by face, weighted (16 slots each). */
    private final BlockState[][] palette = new BlockState[3][];
    private final Warren warren;
    private final boolean retrofit;
    /** Blocks put in. */
    public int placed;
    /** Whether some hole was beside a chunk not loaded, and left: the room is walled up again later. */
    public boolean deferred;

    private RoomShoring(Warren warren, LongSet others, boolean retrofit) {
        this.warren = warren;
        this.others = others;
        this.retrofit = retrofit;
    }

    /** A template cell a rat (or the world) can see through: anything but a whole solid block. */
    public static boolean open(BlockState s) {
        return s.isAir() || !s.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    /** Open ground outside a room that walling up takes: air, and the grass, vines, lichen and roots of a cave or a hillside. */
    public static boolean wild(BlockState s) {
        if (s.isAir()) {
            return true;
        }
        return s.canBeReplaced() && s.getFluidState().isEmpty() && !s.hasBlockEntity() && !s.is(Blocks.LIGHT)
                && (s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.REPLACEABLE_BY_TREES) || s.is(BlockTags.SMALL_FLOWERS) || DigJob.natural(s));
    }

    /** What pours or tumbles into a room: water, lava (and what grows in them), loose natural sand and gravel. */
    public static boolean flooding(BlockState s) {
        return s.getBlock() instanceof LiquidBlock || !s.getFluidState().isEmpty() && s.canBeReplaced()
                || s.getBlock() instanceof FallingBlock && DigJob.natural(s) && s.getFluidState().isEmpty();
    }

    /**
     * The shoring for a room placed at {@code pl}: its holes worked out from its template. {@code shared} are the room's
     * cells it leaves to the rooms it opens into (DigJob's keep-out): theirs, not its.
     */
    public static RoomShoring of(ServerLevel level, PlanPlacement pl, List<RoomTemplates.Entry> entries, LongSet shared, Warren warren) {
        return of(level, pl, entries, shared, warren, false);
    }

    private static RoomShoring of(ServerLevel level, PlanPlacement pl, List<RoomTemplates.Entry> entries, LongSet shared, Warren warren,
                                  boolean retrofit) {
        RoomShoring s = new RoomShoring(warren, warren.roomCells(level, pl.box().inflatedBy(1), pl.box()), retrofit);
        LongOpenHashSet openLocal = new LongOpenHashSet();
        for (RoomTemplates.Entry e : entries) {
            if (open(e.state())) {
                openLocal.add(e.local().asLong());
            }
        }
        LongOpenHashSet doors = doorways(pl, openLocal);
        for (RoomTemplates.Entry e : entries) {
            long w = pl.world(e.local()).asLong();
            s.room.add(w);
            (openLocal.contains(e.local().asLong()) ? s.planOpen : s.planSolid).add(w);
        }
        for (RoomTemplates.Entry e : entries) {
            BlockPos p = pl.world(e.local());
            if (!openLocal.contains(e.local().asLong()) || shared.contains(p.asLong())) {
                continue;
            }
            s.open.add(p);
            for (Direction d : Direction.values()) {
                long n = p.relative(d).asLong();
                if (s.room.contains(n) || doors.contains(n) || s.others.contains(n)) {
                    continue;
                }
                byte face = d == Direction.DOWN ? FLOOR : d == Direction.UP ? CEILING : WALL;
                byte was = s.holes.getOrDefault(n, Byte.MAX_VALUE);
                s.holes.put(n, (byte) Math.min(was, face));
            }
        }
        s.palettes(pl, entries, openLocal);
        return s;
    }

    /**
     * The cells just outside the room's doorways (its entry door on the template's west face, and its exits): each
     * doorway's opening is the open cells in its face joined to the doorway cell.
     */
    public static LongOpenHashSet doorways(PlanPlacement pl, LongSet openLocal) {
        LongOpenHashSet out = new LongOpenHashSet();
        Rooms.Room r = pl.room();
        List<Rooms.Spot> spots = new ArrayList<>(r.exits());
        spots.add(new Rooms.Spot(r.doorX(), r.doorY(), r.doorZ(), Direction.WEST));
        for (Rooms.Spot sp : spots) {
            Direction f = sp.facing();
            BlockPos start = new BlockPos(sp.x(), sp.y(), sp.z());
            ArrayDeque<BlockPos> q = new ArrayDeque<>();
            LongOpenHashSet seen = new LongOpenHashSet();
            q.add(start);
            seen.add(start.asLong());
            while (!q.isEmpty() && seen.size() <= 64) {
                BlockPos c = q.poll();
                out.add(pl.world(c.relative(f)).asLong());
                for (Direction d : Direction.values()) {
                    if (d.getAxis() == f.getAxis()) {
                        continue;                                // stay in the doorway's face
                    }
                    BlockPos m = c.relative(d);
                    if (openLocal.contains(m.asLong()) && seen.add(m.asLong())) {
                        q.add(m);
                    }
                }
            }
        }
        return out;
    }

    /** The room's floor, ceiling and wall blocks: the whole plain blocks its open space shows on each face. */
    private void palettes(PlanPlacement pl, List<RoomTemplates.Entry> entries, LongSet openLocal) {
        List<Map<BlockState, Integer>> counts = List.of(new HashMap<>(), new HashMap<>(), new HashMap<>());
        for (RoomTemplates.Entry e : entries) {
            BlockState st = e.state();
            if (e.nbt() != null || open(st) || st.hasBlockEntity() || st.getLightEmission() > 0 || st.getBlock() instanceof FallingBlock
                    || st.is(net.neoforged.neoforge.common.Tags.Blocks.ORES) || !st.getFluidState().isEmpty() || st.is(BlockTags.LEAVES)) {
                continue;
            }
            BlockPos p = e.local();
            BlockState now = st.rotate(pl.rotation());
            if (openLocal.contains(p.above().asLong())) {
                counts.get(FLOOR).merge(now, 1, Integer::sum);
            }
            if (openLocal.contains(p.below().asLong())) {
                counts.get(CEILING).merge(now, 1, Integer::sum);
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (openLocal.contains(p.relative(d).asLong())) {
                    counts.get(WALL).merge(now, 1, Integer::sum);
                    break;
                }
            }
        }
        for (byte f = 0; f < 3; f++) {
            palette[f] = weighted(counts.get(f));
        }
        BlockState[] fallback = palette[WALL] != null ? palette[WALL] : palette[FLOOR] != null ? palette[FLOOR] : palette[CEILING];
        if (fallback == null) {
            fallback = new BlockState[]{Blocks.COBBLESTONE.defaultBlockState()};
        }
        for (byte f = 0; f < 3; f++) {
            if (palette[f] == null) {
                palette[f] = fallback;
            }
        }
    }

    /** The commonest few blocks (each at least a sixth of the commonest's share), spread over 16 slots by how common. */
    @Nullable
    private static BlockState[] weighted(Map<BlockState, Integer> counts) {
        if (counts.isEmpty()) {
            return null;
        }
        List<Map.Entry<BlockState, Integer>> top = new ArrayList<>(counts.entrySet());
        top.sort(Map.Entry.<BlockState, Integer>comparingByValue().reversed());
        int first = top.get(0).getValue();
        top.removeIf(en -> en.getValue() * 6 < first);
        if (top.size() > 3) {
            top = top.subList(0, 3);
        }
        int total = top.stream().mapToInt(Map.Entry::getValue).sum();
        BlockState[] out = new BlockState[16];
        int k = 0;
        for (Map.Entry<BlockState, Integer> en : top) {
            int n = Math.max(1, Math.round(16f * en.getValue() / total));
            for (int i = 0; i < n && k < 16; i++) {
                out[k++] = en.getKey();
            }
        }
        while (k < 16) {
            out[k++] = top.get(0).getKey();
        }
        return out;
    }

    /**
     * The room's open cell {@code p} was just carved (or found open): wall up what is outside the room round it. In a
     * doorway or a tunnel only what floods or tumbles in; elsewhere the open ground as well.
     */
    public void around(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.relative(d);
            long k = n.asLong();
            if (room.contains(k) || others.contains(k)) {
                continue;
            }
            if (!level.isLoaded(n)) {
                deferred |= holes.containsKey(k);
                continue;
            }
            BlockState s = level.getBlockState(n);
            byte face = holes.getOrDefault(k, (byte) -1);
            if (!retrofit && flooding(s)) {
                wall(level, n, face >= 0 ? face : d == Direction.DOWN ? FLOOR : d == Direction.UP ? CEILING : WALL);
                continue;
            }
            if (face < 0 || !wild(s) || face == WALL && passage(level, n)) {
                continue;
            }
            wall(level, n, face);
        }
    }

    private void wall(ServerLevel level, BlockPos n, byte face) {
        BlockState[] pal = palette[face];
        long h = n.asLong() * 0x9E3779B97F4A7C15L;
        BlockState lining = pal[(int) ((h >>> 60) % pal.length)];
        Clearance.before(level, n, lining);                      // whatever swims or stands in the hole is set down clear of it
        level.setBlock(n, lining, Block.UPDATE_CLIENTS);
        warren.shore(n.asLong());
        placed++;
    }

    /**
     * Whether the wall hole {@code n} is a way into the room rather than a hole in it: with the open (or already walled)
     * holes it joins, no more than {@link #PASSAGE} cells, all in the room's wall, and every one at its foot on ground
     * both outside and in the room beside it: a tunnel mouth at floor level.
     */
    private boolean passage(ServerLevel level, BlockPos n) {
        LongOpenHashSet seen = new LongOpenHashSet();
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        seen.add(n.asLong());
        q.add(n);
        while (!q.isEmpty()) {
            BlockPos c = q.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos m = c.offset(dx, dy, dz);
                        long k = m.asLong();
                        if (!holes.containsKey(k) || seen.contains(k)) {
                            continue;
                        }
                        if (!warren.shored(k) && !(level.isLoaded(m) && wild(level.getBlockState(m)))) {
                            continue;                            // rock, or something built there: not part of the opening
                        }
                        if (holes.get(k) != WALL || !seen.add(k) || seen.size() > PASSAGE) {
                            return false;                        // in the floor or the ceiling, or too big: a hole
                        }
                        q.add(m);
                    }
                }
            }
        }
        for (long k : seen) {
            BlockPos c = BlockPos.of(k);
            if (seen.contains(c.below().asLong())) {
                continue;
            }
            if (!ground(level, c.below())) {
                return false;                                    // nothing to walk on: a gap over a drop
            }
            boolean fromFloor = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                BlockPos in = c.relative(d);
                if (planOpen.contains(in.asLong()) && ground(level, in.below())) {
                    fromFloor = true;
                    break;
                }
            }
            if (!fromFloor) {
                return false;                                    // high in the wall: a hole, not a way in
            }
        }
        return narrow(level, seen);
    }

    /**
     * Whether {@code p} is ground to stand on: the room's plan where it is the room's (the dig may not have got there
     * yet: rats gnaw in batches here and there, not cell by cell in order), else the world's own.
     */
    private boolean ground(ServerLevel level, BlockPos p) {
        long k = p.asLong();
        if (room.contains(k)) {
            return planSolid.contains(k);
        }
        return level.isLoaded(p) && level.getBlockState(p).isFaceSturdy(level, p, Direction.UP);
    }

    /**
     * Whether what opens behind an opening is a tunnel's worth: within {@link #BEHIND} of it, no more open cells than
     * a tunnel of its size running that far away (and a turn), where a cave behind a small gap in a ragged wall
     * opens out wide.
     */
    private boolean narrow(ServerLevel level, LongOpenHashSet mouth) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (long k : mouth) {
            x0 = Math.min(x0, BlockPos.getX(k));
            y0 = Math.min(y0, BlockPos.getY(k));
            z0 = Math.min(z0, BlockPos.getZ(k));
            x1 = Math.max(x1, BlockPos.getX(k));
            y1 = Math.max(y1, BlockPos.getY(k));
            z1 = Math.max(z1, BlockPos.getZ(k));
        }
        BoundingBox near = new BoundingBox(x0, y0, z0, x1, y1, z1).inflatedBy(BEHIND);
        int most = (BEHIND + 1) * mouth.size();
        LongOpenHashSet behind = new LongOpenHashSet(mouth);
        ArrayDeque<BlockPos> q = new ArrayDeque<>();
        for (long k : mouth) {
            q.add(BlockPos.of(k));
        }
        while (!q.isEmpty()) {
            BlockPos c = q.poll();
            for (Direction d : Direction.values()) {
                BlockPos m = c.relative(d);
                long k = m.asLong();
                if (!near.isInside(m) || room.contains(k) || behind.contains(k)) {
                    continue;
                }
                if (!warren.shored(k) && !(level.isLoaded(m) && wild(level.getBlockState(m)))) {
                    continue;
                }
                behind.add(k);
                if (behind.size() - mouth.size() > most) {
                    return false;                                // it opens out: a cave, not a tunnel
                }
                q.add(m);
            }
        }
        return true;
    }

    /** How far behind an opening {@link #narrow} looks. */
    private static final int BEHIND = 3;

    /**
     * A room dug before the shoring gets it now, once: every open cell of it still open is walled round, air and plants
     * only (the water and loose ground were shored as it was dug; anything else there is the Warlord's). Returns how
     * many blocks went in, or -1 if the room is not all loaded yet (try again later).
     */
    public static int wallUp(ServerLevel level, Building b, Warren warren) {
        if (b.door() == null || b.facing() == null) {
            return 0;                                            // dug before rooms kept their door: its placing is unknown
        }
        Rooms.Room r = Rooms.get(b.room());
        if (r == null) {
            return 0;
        }
        PlanPlacement pl = PlanPlacement.compute(r, b.door(), b.facing());
        BoundingBox around = pl.box().inflatedBy(1);
        if (!level.hasChunksAt(around.minX(), around.minZ(), around.maxX(), around.maxZ())) {
            return -1;
        }
        RoomShoring s = of(level, pl, RoomTemplates.get(level, b.room()), new LongOpenHashSet(), warren, true);
        for (BlockPos p : s.open) {
            if (open(level.getBlockState(p))) {
                s.around(level, p);
            }
        }
        return s.placed;
    }
}
