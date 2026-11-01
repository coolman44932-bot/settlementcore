package claude.settlementcore.room;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A raised building made in code round an open court (docs/plans/greatplan-first-spawning.md, J P4: the ring round the pool the
 * user chose): a stepped frame of any size, one room per court size, registered as a {@link Rooms#family} so a ring fits the pool the
 * player dug. The court, {@code courtX} deep by {@code courtZ} wide, is left open: no cell of the template is in its columns, and
 * {@link Rooms#court} names it, so the raise never builds on it, clears it, levels it or tears it down (core/room/Siting).
 *
 * <p>Template-local, as every room: x deep from the door's face (x=0), y up, z wide; the door at standing height in the middle
 * of the x=0 face. Round the court, {@code steps} rings of terraces, each a block higher than the one outside it, on a ground
 * layer ({@code floor}, its outer edge {@code rim}) that is solid in every column but the court's; the innermost terrace, the
 * highest, is the court's edge. A flight of {@code stair} climbs from the door to it, cut through each terrace on the door's
 * line, and a {@code lamp} stands on the innermost terrace at each corner of the court. Every other cell of the box is air, so
 * what stands in it is cleared.
 */
public final class CourtRing {

    /** The blocks of a ring: the ground layer, its outer edge, the terraces (outermost first; the last repeats), the stair, the lamps. */
    public record Style(BlockState floor, BlockState rim, List<BlockState> terraces, BlockState stair, @Nullable BlockState lamp) {
        BlockState terrace(int k) {
            return terraces.get(Math.min(k - 1, terraces.size() - 1));
        }
    }

    /** What every ring of a family is: its name, price, function and description, and how many terraces. */
    public record Kind(String prefix, String displayName, int cost, Rooms.Function function, String description, int steps, Style style) {
    }

    /** The smallest and largest court a ring is raised round (columns each way). */
    public static final int COURT_MIN = 3, COURT_MAX = 24;

    private CourtRing() {
    }

    /** The id of {@code kind}'s ring round a court {@code courtX} deep by {@code courtZ} wide: "lm_pool_ring_5x7". */
    public static String id(Kind kind, int courtX, int courtZ) {
        return kind.prefix() + "_" + courtX + "x" + courtZ;
    }

    /** The court's size an id of {@code kind}'s names, {x, z}; null for an id that is not one of its sizes. */
    @Nullable
    public static int[] courtOf(Kind kind, String id) {
        String head = kind.prefix() + "_";
        if (!id.startsWith(head)) {
            return null;
        }
        String[] xz = id.substring(head.length()).split("x");
        if (xz.length != 2) {
            return null;
        }
        try {
            int x = Integer.parseInt(xz[0]);
            int z = Integer.parseInt(xz[1]);
            if (x < COURT_MIN || z < COURT_MIN || x > COURT_MAX || z > COURT_MAX || !id.equals(id(kind, x, z))) {
                return null;                                    // "05x7" is not 5x7's id: one id per size
            }
            return new int[]{x, z};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code kind}'s rings into the engine, as {@code namespace}'s raised buildings: one for every court size, made when first asked for. */
    public static void register(String namespace, Kind kind) {
        Rooms.family(namespace, kind.prefix() + "_", id -> make(kind, id));
    }

    /** The ring an id names, or null for none of {@code kind}'s. */
    @Nullable
    public static Rooms.Made make(Kind kind, String id) {
        int[] c = courtOf(kind, id);
        if (c == null) {
            return null;
        }
        int n = Math.max(1, kind.steps());
        int band = n + 1;                                       // the bare ground layer's ring, then a terrace a ring
        int sx = c[0] + 2 * band;
        int sz = c[1] + 2 * band;
        int sy = n + 2;                                         // the ground layer, the terraces, the lamps' row
        int doorZ = sz / 2;
        Style st = kind.style();
        BlockState air = Blocks.AIR.defaultBlockState();
        BoundingBox court = new BoundingBox(band, 0, band, band + c[0] - 1, sy - 1, band + c[1] - 1);
        List<RoomTemplates.Entry> cells = new ArrayList<>();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    if (court.isInside(x, court.minY(), z)) {
                        continue;                               // the court: no cell at all, at any height
                    }
                    int ring = Math.min(Math.min(x, sx - 1 - x), Math.min(z, sz - 1 - z));   // 0 at the outer edge
                    int height = Math.min(ring, n);                                         // the terrace's top
                    BlockState s;
                    if (y == 0) {
                        s = ring == 0 ? st.rim() : st.floor();
                    } else if (y <= height) {
                        boolean flight = z == doorZ && x == ring && x <= n;                 // the stair from the door, on its line
                        s = flight && y == height ? st.stair().trySetValue(StairBlock.FACING, Direction.EAST) : st.terrace(y);
                    } else if (y == n + 1 && st.lamp() != null && ring == n && corner(x, z, court)) {
                        s = st.lamp();
                    } else {
                        s = air;
                    }
                    cells.add(new RoomTemplates.Entry(new BlockPos(x, y, z), s, null));
                }
            }
        }
        Rooms.Room room = new Rooms.Room(id, kind.displayName(), kind.cost(), kind.function(),
                String.format(Locale.ROOT, "%s Round a court %d by %d.", kind.description(), c[0], c[1]),
                sx, sy, sz, 0, 1, doorZ, List.of(), List.of(), List.of());
        return new Rooms.Made(room, List.copyOf(cells), court);
    }

    /** Whether (x, z), on the innermost terrace, is one of the four cells at the court's corners. */
    private static boolean corner(int x, int z, BoundingBox court) {
        boolean xEdge = x == court.minX() - 1 || x == court.maxX() + 1;
        boolean zEdge = z == court.minZ() - 1 || z == court.maxZ() + 1;
        return xEdge && zEdge;
    }
}
