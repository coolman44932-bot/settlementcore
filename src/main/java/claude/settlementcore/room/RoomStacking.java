package claude.settlementcore.room;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.List;

/**
 * Rooms dug over one another (user, 2026-09-26: "i accidentally went above another room": a Slave Fields dug over the
 * Beast Pens came out with a 20 by 9 hole in its floor, down into the pens). Rooms may overlap, and a dig leaves what is
 * open in the other rooms alone so that they open into each other (WarrenHeartBlockEntity#keepOutCells). Beside a room
 * that is what it should do, but over one it left the upper room's floor out wherever the lower one had air, and a room
 * dug under another cut the upper one's floor away.
 *
 * <p>Now the upper room's floor is laid as the lower room's ceiling wherever it lies at least {@link #HEADROOM} blocks
 * over where one stands in the lower room (its doorway's level), whichever of the two was dug first. A room dug over
 * another has its floor, and all of it above, dug through the other's air; one dug under another leaves the other's floor,
 * and all of that room above it, standing. Nearer than that the two still open into each other, as rooms side by side
 * do; a way up or down ({@link #verticalRoute}) keeps its air, and a room over one opens into it. Nothing with an
 * inventory is ever built over. Rooms dug over one another before this get the floor between them laid once ({@link
 * #mend}, WarrenHeartBlockEntity#stackRooms).
 */
public final class RoomStacking {

    /** How far over where one stands in the lower room the upper room's floor must lie to be laid as its ceiling. */
    public static final int HEADROOM = 3;

    private RoomStacking() {
    }

    /** A way up or down, whose air is the way: a shaft, stairs, the deep levels' holes. */
    public static boolean verticalRoute(String room) {
        return room.equals("gnaw_hole_shaft") || room.equals("tunnel_stairs") || room.equals("deep_hole");
    }

    /** A column of the world (x, z), as one long. */
    public static long column(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    private static int columnX(long k) {
        return (int) (k >> 32);
    }

    private static int columnZ(long k) {
        return (int) k;
    }

    /** Where nothing stands in the way: air, what has no collision (a crop, a torch), a chain or a hanging lamp. */
    public static boolean loose(BlockState s) {
        return s.isAir() || s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).isEmpty()
                || s.is(Blocks.CHAIN) || s.getBlock() instanceof LanternBlock;
    }

    /** For each column of a room's footprint, the lowest cell of its floor there (anything it builds below its doorway). */
    static Long2IntOpenHashMap floors(ServerLevel level, PlanPlacement pl) {
        Long2IntOpenHashMap out = new Long2IntOpenHashMap();
        out.defaultReturnValue(Integer.MAX_VALUE);
        int stand = pl.door().getY();
        for (RoomTemplates.Entry e : RoomTemplates.get(level, pl.room().id())) {
            if (e.state().isAir()) {
                continue;
            }
            BlockPos w = pl.world(e.local());
            if (w.getY() >= stand) {
                continue;                                        // its walls and ceiling, not its floor
            }
            long k = column(w.getX(), w.getZ());
            if (w.getY() < out.get(k)) {
                out.put(k, w.getY());
            }
        }
        return out;
    }

    /**
     * Where {@code upper}'s floor lies over {@code lower}, far enough over where one stands in it to be its ceiling: the
     * columns, each with the lowest cell of the floor there. Empty when either is a way up or down, or they don't overlap.
     */
    public static Long2IntOpenHashMap over(ServerLevel level, PlanPlacement upper, PlanPlacement lower) {
        Long2IntOpenHashMap out = new Long2IntOpenHashMap();
        BoundingBox lb = lower.box();
        if (verticalRoute(upper.room().id()) || verticalRoute(lower.room().id()) || !upper.box().intersects(lb)) {
            return out;
        }
        int stand = lower.door().getY();
        for (Long2IntMap.Entry e : floors(level, upper).long2IntEntrySet()) {
            int x = columnX(e.getLongKey()), z = columnZ(e.getLongKey()), y = e.getIntValue();
            if (x >= lb.minX() && x <= lb.maxX() && z >= lb.minZ() && z <= lb.maxZ()
                    && y >= stand + HEADROOM && y <= lb.maxY()) {
                out.put(e.getLongKey(), y);
            }
        }
        return out;
    }

    /**
     * The cells a dig of {@code dig} leaves alone ({@code keep}: the other rooms' open space) with {@code other} over or
     * under it. Over it: its own floor and all of it above are dug through the other's air, bar anything with an
     * inventory. Under it: the other's floor and all of that room above it stay.
     */
    public static void adjust(ServerLevel level, LongSet keep, PlanPlacement dig, PlanPlacement other) {
        int top = Math.min(dig.box().maxY(), other.box().maxY());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (Long2IntMap.Entry e : over(level, dig, other).long2IntEntrySet()) {
            for (int y = e.getIntValue(); y <= top; y++) {
                m.set(columnX(e.getLongKey()), y, columnZ(e.getLongKey()));
                if (level.getBlockEntity(m) == null) {
                    keep.remove(m.asLong());
                }
            }
        }
        for (Long2IntMap.Entry e : over(level, other, dig).long2IntEntrySet()) {
            for (int y = e.getIntValue(); y <= top; y++) {
                keep.add(BlockPos.asLong(columnX(e.getLongKey()), y, columnZ(e.getLongKey())));
            }
        }
    }

    /**
     * Lays the floor between {@code later} and {@code earlier}, dug before the rooms knew how to stack (left out where
     * one was dug over the other, or cut away where one was dug under it), with all of the upper room above it there.
     * Only columns where the floor is missing are touched. Returns the blocks put in, or -1 if a chunk it needs is not
     * loaded (nothing was changed then).
     */
    public static int mend(ServerLevel level, PlanPlacement later, PlanPlacement earlier) {
        List<List<DigJob.Task>> holes = new ArrayList<>();
        if (!holes(level, later, earlier, holes) || !holes(level, earlier, later, holes)) {
            return -1;
        }
        int placed = 0;
        for (List<DigJob.Task> col : holes) {
            for (DigJob.Task t : col) {
                if (level.getBlockState(t.pos()).equals(t.state()) || level.getBlockEntity(t.pos()) != null) {
                    continue;
                }
                DigJob.place(level, t);
                placed++;
            }
        }
        if (placed > 0) {
            RoomRelight.joinUp(level, later, RoomTemplates.get(level, later.room().id()));
            RoomRelight.joinUp(level, earlier, RoomTemplates.get(level, earlier.room().id()));
        }
        return placed;
    }

    /** The columns where {@code upper}'s floor over {@code lower} is missing, each as the upper room's cells from its floor up (inside the lower room). */
    private static boolean holes(ServerLevel level, PlanPlacement upper, PlanPlacement lower, List<List<DigJob.Task>> out) {
        Long2IntOpenHashMap cols = over(level, upper, lower);
        if (cols.isEmpty()) {
            return true;
        }
        int top = lower.box().maxY();
        Long2ObjectOpenHashMap<List<DigJob.Task>> byColumn = new Long2ObjectOpenHashMap<>();
        for (RoomTemplates.Entry e : RoomTemplates.get(level, upper.room().id())) {
            BlockPos w = upper.world(e.local());
            long k = column(w.getX(), w.getZ());
            if (cols.containsKey(k) && w.getY() >= cols.get(k) && w.getY() <= top) {
                byColumn.computeIfAbsent(k, c -> new ArrayList<>()).add(new DigJob.Task(w, e.state().rotate(upper.rotation()), e.nbt()));
            }
        }
        int stand = upper.door().getY();
        for (List<DigJob.Task> col : byColumn.values()) {
            boolean hole = false;
            for (DigJob.Task t : col) {
                if (!level.isLoaded(t.pos())) {
                    return false;
                }
                if (t.pos().getY() < stand && !t.state().isAir()) {
                    BlockState was = level.getBlockState(t.pos());
                    hole |= !was.is(t.state().getBlock()) && loose(was);
                }
            }
            if (hole) {
                out.add(col);
            }
        }
        return true;
    }
}
