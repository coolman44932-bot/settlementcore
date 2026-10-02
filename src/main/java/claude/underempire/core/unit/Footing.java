package claude.underempire.core.unit;

import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Whether the cell a settler comes up in has a way out of it: the one question behind the picks of where a defender (Muster.cell), a person
 * of the brood (Brood.bringUp) and a slinger out of the horde (Settlement.findStandable) come up. {@link SettlerMob#standable} says a settler
 * fits in a cell, but a standable cell can be in a hollow shut in on every side (a trap's pocket, a hole in the rock, the cell a foe is sealed
 * in), and whoever comes up there comes up in it with whatever else is shut in (a defender rose on a husk sealed in stone and killed it; the
 * first rule against it, 8f199d2, refused only a cell of one, and a sealed hollow of two cells or more still passed).
 *
 * <p>{@link #escapes} floods out from a cell over the cells a settler can step into, and gives up when it has found {@link #LIMIT} of them
 * <b>to stand in</b> (cells with a floor under them: room enough, it is no pocket), or reached the open sky, or a cell already known to have a
 * way out, or ground that is not loaded (not seen to be shut in). A flood that runs dry short of that is a sealed hollow. The cells it walks:
 * <ul>
 *   <li>a <b>body space</b>: a cell with room for a settler in it, two high, with nothing in the way but something low enough to step onto
 *   (a slab, a carpet, a layer of snow: half a block or less, and then the cell over the settler's head must be free too);</li>
 *   <li>side to side, or up or down a step of one block (the block under the higher cell no taller than a block: a stair or a full block is
 *   a step, a fence or a wall is not). The roof is not asked for a jump, so a step of a block in a low tunnel counts;</li>
 *   <li>no floor asked to walk over a cell: a cell over a drop is as good as any other to the flood, which carries on over it (a mob steps off
 *   a ledge, and a passage with a hole in its floor is still a passage). Nor any move up or down in place: a shaft with nothing walkable at its
 *   side is one cell, and the cell at its foot is refused, as it always was.</li>
 * </ul>
 * But only a cell with a floor under it counts toward the limit ({@link #floored}): a body does not stand in the air over the floor of a tall
 * hollow, so a sealed hollow of 3 by 3 by 4, of 2 by 2 by 8 or of 1 by 12 by 3 cells (24 or more cells of air a body fits in, and under 24 to
 * stand in) is no more room than a pocket of two (the review of 18f90e3: the flood counted every cell it walked, floating air layers too).
 * Water and lava have no collision, so they are open space to it, as they were to the check it replaces; {@link SettlerMob#standable} keeps
 * a settler out of the cell itself.
 *
 * <p>The cost is bounded: the cells with a floor are walked before the ones without, so a flood in a room looks at little more than
 * {@link #LIMIT} cells and a flood in a hollow with fewer to stand in at no more than {@link #ROOM} times as many in all, each once, and a
 * {@link Known} kept for the length of one pick lets the candidates of that pick share what each fill found (every cell it looked at has the
 * same answer, its way out being the same), so a pick of dozens of cells costs little more than a fill or two.
 */
public final class Footing {

    /** The cells to stand in a flood must find for a place to be room enough: a hollow shut in on every side with fewer is a pocket. */
    public static final int LIMIT = 24;
    /** The cells a flood looks at in all, floorless ones too, per cell of the limit: a void this big is no pocket, whatever its floor. */
    public static final int ROOM = 8;
    /** The tallest thing a settler walks onto without a jump: a slab, a carpet, up to four layers of snow. */
    private static final double LOW = 0.51;
    /** Under this, a thing underfoot (a carpet) lifts a settler too little to want the cell over its head. */
    private static final double FLAT = 0.1;
    /** The tallest the block under a cell may stand for the cell to be a step up from the one beside it: a full block or a stair. */
    private static final double STEP = 1.001;
    /** How many cells high the body is that a settler needs: every settler is under two blocks tall. */
    public static final int BODY = 2;
    private static final int[] SIDEWAYS = {0, 1, -1};

    private Footing() {
    }

    /**
     * What one pick has found out already: which cells have a way out and which are shut in, so the candidates of one pick (Muster.cell,
     * Settlement.findStandable) share the work. Never kept past the tick the pick is in: it does not watch the world for changes. It is of
     * one height of body ({@link #escapes(Level, BlockPos, int, Known, int)}): a body of another height has other ways out.
     */
    public static final class Known {
        private final Long2BooleanOpenHashMap said = new Long2BooleanOpenHashMap();
        private int looked;

        /** How many cells the floods looked at in all (for tests: the cost of a pick). */
        public int looked() {
            return looked;
        }
    }

    /** {@link #escapes(Level, BlockPos, int, Known)} with nothing known beforehand. */
    public static boolean escapes(Level level, BlockPos pos, int limit) {
        return escapes(level, pos, limit, new Known());
    }

    /** {@link #escapes(Level, BlockPos, int, Known, int)} for a settler, a body of {@link #BODY} cells. */
    public static boolean escapes(Level level, BlockPos pos, int limit, Known known) {
        return escapes(level, pos, limit, known, BODY);
    }

    /**
     * Whether a settler at {@code pos} has a way out: the cells it can walk to (see the class) number {@code limit} or more with a floor
     * under them, or reach the open sky, a cell {@code known} to have a way out, or ground that is not loaded. False for a hollow that closes
     * off short of that, and for a cell no settler fits in. Every cell the flood looks at is told to {@code known}.
     *
     * <p>{@code height} is how many cells high the body is that walks (a settler's {@link #BODY}, but a foe's own: a spider walks a gap
     * a cell high, a tall foe needs three): the cells it flows over are those {@link #fits} for a body that high.
     */
    public static boolean escapes(Level level, BlockPos pos, int limit, Known known, int height) {
        long start = pos.asLong();
        if (known.said.containsKey(start)) {
            return known.said.get(start);
        }
        if (!level.isLoaded(pos)) {
            return true;                                         // ground not seen is not ground seen to be shut in (and not to be loaded by asking)
        }
        if (!body(level, pos, height)) {
            return false;
        }
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue floor = new LongArrayFIFOQueue();     // the cells with a floor are walked before the ones without: a room's flood
        LongArrayFIFOQueue air = new LongArrayFIFOQueue();       // finds its limit along the floor, and not up through the air over it
        seen.add(start);
        int standing = 0;                                        // the cells found that a body stands in
        if (floored(level, pos)) {
            standing = 1;
            floor.enqueue(start);
        } else {
            air.enqueue(start);
        }
        boolean out = limit <= 1;
        BlockPos.MutableBlockPos side = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos to = new BlockPos.MutableBlockPos();
        search:
        while (!out && !(floor.isEmpty() && air.isEmpty())) {
            BlockPos at = BlockPos.of((floor.isEmpty() ? air : floor).dequeueLong());
            if (SettlerMob.underOpenSky(level, at)) {
                out = true;
                break;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                side.setWithOffset(at, d);
                if (!level.isLoaded(side)) {
                    out = true;
                    break search;
                }
                for (int dy : SIDEWAYS) {
                    to.set(side.getX(), side.getY() + dy, side.getZ());
                    long key = to.asLong();
                    if (seen.contains(key) || !body(level, to, height)) {
                        continue;
                    }
                    if (dy != 0 && top(level, (dy > 0 ? to : at).below()) > STEP) {
                        continue;                                // more than a step: a fence or a wall under the higher cell
                    }
                    if (known.said.containsKey(key) && known.said.get(key)) {
                        out = true;
                        break search;
                    }
                    seen.add(key);
                    boolean stands = floored(level, to);
                    if (stands) {
                        standing++;
                    }
                    if (standing >= limit || seen.size() >= limit * ROOM) {
                        out = true;
                        break search;
                    }
                    (stands ? floor : air).enqueue(key);
                }
            }
        }
        known.looked += seen.size();
        for (LongIterator it = seen.iterator(); it.hasNext(); ) {
            known.said.put(it.nextLong(), out);                 // all one place: each cell has the same way out as the cell the flood began at
        }
        return out;
    }

    /**
     * Whether a body in the cell {@code p} (a body space) stands on something: a low thing in the cell itself (a slab, a carpet), or a floor
     * under it too tall to be a cell of its own (a block, a stair). A cell over a drop, or over a low thing that is a cell of its own, is not.
     */
    private static boolean floored(Level level, BlockPos p) {
        return top(level, p) > 0 || top(level, p.below()) > LOW;
    }

    /** Whether a body {@code height} cells high has room at {@code pos}, the cell its feet are in (see the class: a body space). */
    public static boolean fits(Level level, BlockPos pos, int height) {
        return body(level, pos, height);
    }

    /** A cell with room for a body {@code height} high: nothing in it but a low thing underfoot, nothing over that, and one cell more free if it lifts. */
    private static boolean body(Level level, BlockPos p, int height) {
        double lift = top(level, p);
        if (lift > LOW) {
            return false;
        }
        for (int i = 1; i < height; i++) {
            if (!free(level, p.above(i))) {
                return false;
            }
        }
        return lift <= FLAT || free(level, p.above(height));
    }

    private static boolean free(Level level, BlockPos p) {
        return level.getBlockState(p).getCollisionShape(level, p).isEmpty();
    }

    /** How high the collision stands in {@code p} (none: 0; a fence or a wall stands over 1, out of its cell). */
    private static double top(Level level, BlockPos p) {
        VoxelShape shape = level.getBlockState(p).getCollisionShape(level, p);
        return shape.isEmpty() ? 0 : shape.max(Direction.Axis.Y);
    }
}
