package claude.settlementcore.room;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How a race walls its settlement (docs/plans/walls.md, E2): what a wall's column is made of from its foot up, what a gate's is,
 * how high, how wide a gate is, and what a column costs in the race's currency. Its settlers raise it along a line or round a
 * ring ({@link WallJob}, {@code Works#raiseWall}), each column on its own ground.
 *
 * <p>{@code column} and {@code gate} answer the block of a column's course {@code course} (0 at its foot, the cell over the
 * ground) of {@code height} courses, the {@code column}'th along the wall, {@code across} pointing across it to the settlement's
 * side; null or air for nothing there. They must answer the same for the same arguments every time: a saved wall is rebuilt from
 * them. A column standing in water is that much higher (its foot on the bed), and its lowest courses are the foot's block again.
 *
 * <p>Registered by the race's mod at start-up ({@link #register}): the style is also a raised building of the race's, made in
 * code ({@link Rooms#family}), its room id {@link #roomId}, so a wall being raised is one of the settlement's sites like any
 * building.
 */
public record WallStyle(String id, String displayName, int height, int cost, int gateWidth, Column column, Column gate) {

    public interface Column {
        @Nullable BlockState at(int column, int course, int height, Direction across);
    }

    public WallStyle {
        if (height < 2) {
            throw new IllegalArgumentException("Settlement Core: the wall style " + id + " is " + height + " high: a mob steps over 1");
        }
        if (gateWidth < 1) {
            throw new IllegalArgumentException("Settlement Core: the wall style " + id + "'s gate is " + gateWidth + " wide");
        }
    }

    private static final Map<String, WallStyle> STYLES = new LinkedHashMap<>();

    /** The id of the raised building a wall of this style is (Rooms): {@code wall_<id>}. */
    public String roomId() {
        return "wall_" + id;
    }

    /** A race's wall style into the engine ({@code namespace}: its own mod id, as for {@link Rooms#register}); a second with the same id wins. */
    public static synchronized void register(String namespace, WallStyle style) {
        STYLES.put(style.id(), style);
        String room = style.roomId();
        Rooms.family(namespace, room, id -> id.equals(room) ? made(style) : null);
    }

    @Nullable
    public static synchronized WallStyle get(@Nullable String id) {
        return id == null ? null : STYLES.get(id);
    }

    /** The building a wall is (one cell: its job lays the wall, column by column, and never reads a template). */
    private static Rooms.Made made(WallStyle s) {
        Rooms.Room r = new Rooms.Room(s.roomId(), s.displayName(), s.cost(), Rooms.Function.of("WALL"),
                "A wall round the settlement, raised by its settlers along the ground.", 1, 1, 1, 0, 0, 0, List.of(), List.of(), List.of());
        return new Rooms.Made(r, List.of(new RoomTemplates.Entry(BlockPos.ZERO, Blocks.AIR.defaultBlockState(), null)), null);
    }
}
