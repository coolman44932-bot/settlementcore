package claude.underempire.core.work;

import claude.underempire.core.room.PlanPlacement;
import claude.underempire.core.room.Rooms;
import claude.underempire.core.settlement.Building;
import claude.underempire.core.settlement.Settlement;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What work each kind of room does: the engine's own rooms (the Furnace Chambers, the Ore Mine, the Hoard, the Fields, the
 * Grove, the Pens, the Cook-Pits and the Orchard), and whatever a race adds for its own (Under-Empire: the Throne Hall, the
 * Fleshpits and the Warlock's Workshop, registered as the mod starts). A room whose kind has no work here is just a room.
 */
public final class RoomWorks {

    /** A room's work, for a finished room of the settlement. */
    @FunctionalInterface
    public interface Factory {
        RoomWork make(Settlement settlement, Building building, PlanPlacement placement);
    }

    private static final Map<Rooms.Function, Factory> BY_FUNCTION = new LinkedHashMap<>();

    static {
        register(Rooms.Function.FORGE, ForgeWork::new);
        register(Rooms.Function.OREMINE, MineWork::new);
        register(Rooms.Function.HOARD, HoardWork::new);
        register(Rooms.Function.FIELDS, FieldsWork::new);
        register(Rooms.Function.GROVE, GroveWork::new);
        register(Rooms.Function.PENS, PensWork::new);
        register(Rooms.Function.KITCHEN, CookWork::new);
        register(Rooms.Function.ORCHARD, OrchardWork::new);
    }

    private RoomWorks() {
    }

    /** The work for rooms of this kind (a later registration replaces an earlier one). */
    public static synchronized void register(Rooms.Function function, Factory make) {
        BY_FUNCTION.put(function, make);
    }

    @Nullable
    public static Factory get(Rooms.Function function) {
        return BY_FUNCTION.get(function);
    }
}
