package claude.settlementcore.settlement;

import claude.settlementcore.room.Rooms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * One room of a settlement, dug or being dug: which room (core/room/Rooms), the box it fills, and its entry door and the way
 * that door faces (null for rooms dug before doors were kept: core/room/DeepLevels#placementOf infers them).
 */
public record Building(String room, BoundingBox box, @Nullable BlockPos door, @Nullable Direction facing) {
    /** A room no race registers and whose door was never kept: where most rooms stand, a floor course over the footing. */
    private static final int UNKNOWN_DOOR_Y = 2;

    public Building(String room, BoundingBox box) {
        this(room, box, null, null);
    }

    /**
     * The level one stands at in this room: its door's cell, which a room is placed with at feet height (core/room/PlanPlacement),
     * and which stays its floor whichever way it was dug, since a room turns only about the up axis. A room dug before doors
     * were kept has it from its template (the door's height over the box's bottom). Aim here whatever is raised into a room
     * or sent back to it, never at a set height over the box's bottom: most rooms stand 2 up, but the Moulder Fleshpits stand
     * 5 up over the vat's pit, and a Rat Ogre grown at 2 up came up inside their floor (2026-09-28).
     */
    public int standingY() {
        if (door != null) {
            return door.getY();
        }
        Rooms.Room r = Rooms.get(room);
        return box.minY() + (r != null ? r.doorY() : UNKNOWN_DOOR_Y);
    }
}
