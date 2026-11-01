package claude.settlementcore.room;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Where a room goes when a gnaw-plan is used. Shared by the client (the outline you see while
 * holding a plan) and the server (the room that gets dug), so the two can never disagree.
 *
 * <p>Every room template has its entry door on the local -x face, extending toward +x. The
 * room is rotated so it extends away from the player along their facing, and moved so the door
 * cell lands on {@code door}: the wall block they aimed at, at their feet height.
 */
public record PlanPlacement(Rooms.Room room, Rotation rotation, BlockPos origin, BoundingBox box, BlockPos door) {

    public static Rotation rotationFor(Direction extendsToward) {
        return switch (extendsToward) {
            case SOUTH -> Rotation.CLOCKWISE_90;
            case WEST -> Rotation.CLOCKWISE_180;
            case NORTH -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public static PlanPlacement compute(Rooms.Room room, BlockPos door, Direction extendsToward) {
        Rotation rot = rotationFor(extendsToward);
        BlockPos doorLocal = new BlockPos(room.doorX(), room.doorY(), room.doorZ());
        BlockPos origin = door.subtract(local(doorLocal, rot));
        BlockPos a = origin.offset(local(BlockPos.ZERO, rot));
        BlockPos b = origin.offset(local(new BlockPos(room.sizeX() - 1, room.sizeY() - 1, room.sizeZ() - 1), rot));
        return new PlanPlacement(room, rot, origin, BoundingBox.fromCorners(a, b), door);
    }

    /**
     * A raised building aimed at the ground (docs/plans/surface-builder.md, section 1): the door sits on the course above
     * {@code aimedGround}, on the template's x=0 face, and the building extends away along {@code extendsToward}.
     */
    public static PlanPlacement onGround(Rooms.Room room, BlockPos aimedGround, Direction extendsToward) {
        return compute(room, aimedGround.above(), extendsToward);
    }

    /** The way the room extends from its door: what {@link #compute} was given ({@link #rotationFor}'s inverse). */
    public Direction facing() {
        return switch (rotation) {
            case CLOCKWISE_90 -> Direction.SOUTH;
            case CLOCKWISE_180 -> Direction.WEST;
            case COUNTERCLOCKWISE_90 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    /** Template-local position to world position. */
    public BlockPos world(BlockPos local) {
        return origin.offset(local(local, rotation));
    }

    private static BlockPos local(BlockPos p, Rotation rot) {
        return StructureTemplate.transform(p, Mirror.NONE, rot, BlockPos.ZERO);
    }
}
