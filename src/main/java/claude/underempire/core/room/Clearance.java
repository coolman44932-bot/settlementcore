package claude.underempire.core.room;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Nobody is built into a wall (the user's new world, 2026-09-29: slaves "suffocated in a wall" while the horde dug).
 * A dig and the shoring beside it put their blocks down with {@code Level#setBlock}, which asks no one what stands in the cell
 * (vanilla's own placing asks first: {@code BlockItem#canPlace} through {@code isUnobstructed}). So a wall gnawed over a cave's
 * open cell, or over the water of a pocket beside a room, went down on whatever was in it: a rat gnawing at the face hugs it,
 * and a wall cell that was cave air a moment before is where its body is. The rat's eye was in a whole block and vanilla's
 * {@code Entity#isInWall} did the rest, a point of damage every half second.
 *
 * <p>{@link #before} is called ahead of every whole block those put down: a mob whose body is in the cell is set down in the
 * nearest clear, dry spot beside it. {@link #clearOf} is the same for a settler that has clawed up out of the earth into a
 * cell that was walled while it climbed (SettlerMob#emergeAt: it comes up 24 ticks after it was told where).
 *
 * <p>Never into a pit, when there is any other spot ({@link #pit}), and not into a cell the job is still to fill (its {@code taken}) before
 * a spot it is not: a builder on a half-laid course stands where the next block goes in, and the nearest dry floor can be an open cell of
 * the layer it is laying, a hole that fills up round it (the step platform's crewed test, about 1 copy in 300: a builder set down in the last
 * open cell of a course was walled in by the blocks of its own batch, and could not climb out until the cell it stood in was laid).
 */
public final class Clearance {

    /** How far, in cells each way, a mob squeezed out of a cell looks for room. */
    public static final int REACH = 6;

    private Clearance() {
    }

    /**
     * {@code now} is about to go into the cell {@code pos}: whoever's body is in it (a mob, a player on a half-built step of a
     * raised building, anything living that isn't riding or passing through walls) is set down clear of it first. Only a block
     * that suffocates (the same test as {@code Entity#isInWall}) into a cell that does not already: no body is in a solid
     * cell, and a slab or a lamp is no wall. Returns how many were moved.
     */
    public static int before(ServerLevel level, BlockPos pos, BlockState now) {
        return before(level, pos, now, null);
    }

    /** {@link #before(ServerLevel, BlockPos, BlockState)}; {@code taken}: the cells the job is still to put a block in (a spot is set down there last). */
    public static int before(ServerLevel level, BlockPos pos, BlockState now, @Nullable Predicate<BlockPos> taken) {
        if (!now.isSuffocating(level, pos) || level.getBlockState(pos).isSuffocating(level, pos)) {
            return 0;
        }
        AABB cell = new AABB(pos);
        // every mob, as before (2026-09-29); and since the surface builder (the review of 51b59f8) a player too, and the rest of
        // the living (an armour stand): a player standing in the next course's cell was built into, feet then head
        List<LivingEntity> in = level.getEntitiesOfClass(LivingEntity.class, cell, m -> m.isAlive() && !m.noPhysics && !m.isPassenger()
                && !m.isSpectator());
        int moved = 0;
        for (LivingEntity m : in) {
            Vec3 to = spot(level, m, pos, cell, pos, taken);
            if (to != null) {
                m.teleportTo(to.x, to.y, to.z);
                if (m instanceof Mob mob) {
                    mob.getNavigation().stop();
                }
                m.setDeltaMovement(Vec3.ZERO);
                moved++;
            }
        }
        return moved;
    }

    /**
     * Where {@code e} must be set down if it stands in a wall (a whole block in its body: something was built there as it
     * climbed out of the earth), or null when it is not walled in. Only a block that suffocates counts, as in
     * {@code before}: a Warlord comes up on his throne, a stair whose collision shape is in his body, and is no less free
     * for it (the first version moved him off it: theDeadliestRatTakesTheThrone).
     */
    @Nullable
    public static Vec3 clearOf(ServerLevel level, Entity e) {
        if (!walledIn(level, e)) {
            return null;
        }
        return spot(level, e, e.blockPosition(), null);
    }

    /** Whether a block that suffocates (the test of {@code Entity#isInWall}) overlaps any of {@code e}'s body. */
    public static boolean walledIn(ServerLevel level, Entity e) {
        AABB box = e.getBoundingBox();
        double d = 1.0E-7;
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(box.minX + d, box.minY + d, box.minZ + d),
                BlockPos.containing(box.maxX - d, box.maxY - d, box.maxZ - d))) {
            BlockState s = level.getBlockState(p);
            if (s.isSuffocating(level, p) && net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(
                    s.getCollisionShape(level, p).move(p.getX(), p.getY(), p.getZ()), net.minecraft.world.phys.shapes.Shapes.create(box),
                    net.minecraft.world.phys.shapes.BooleanOp.AND)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The nearest cell to {@code around} where the whole of {@code e} fits, dry, on a floor that holds it, and (with
     * {@code avoid}) clear of that box: on the nearest ring of cells that has one, the cell nearest {@code e}. Null if there is
     * none within {@link #REACH}.
     */
    @Nullable
    public static Vec3 spot(ServerLevel level, Entity e, BlockPos around, @Nullable AABB avoid) {
        return spot(level, e, around, avoid, null, null);
    }

    /**
     * {@link #spot(ServerLevel, Entity, BlockPos, AABB)} that passes over a spot with no way out ({@link #pit}, with {@code filled}, the cell a
     * block is about to go into, counted as a wall), and a cell the job is still to fill ({@code taken}), for any farther one within {@link #REACH}
     * that is neither; with none, the nearest taken one of the first ring that has any, else the nearest pit of the first ring that has any.
     */
    @Nullable
    public static Vec3 spot(ServerLevel level, Entity e, BlockPos around, @Nullable AABB avoid, @Nullable BlockPos filled,
                            @Nullable Predicate<BlockPos> taken) {
        EntityDimensions size = e.getDimensions(Pose.STANDING);
        Vec3 onTaken = null;
        Vec3 pitted = null;
        for (int r = 0; r <= REACH; r++) {
            List<BlockPos> fits = new ArrayList<>();
            List<BlockPos> takens = new ArrayList<>();
            List<BlockPos> pits = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;                                // the cells inside were looked at on the rings before
                    }
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos p = around.offset(dx, dy, dz);
                        if (!level.isLoaded(p)) {
                            continue;
                        }
                        AABB box = size.makeBoundingBox(Vec3.atBottomCenterOf(p));
                        BlockPos below = p.below();
                        if ((avoid == null || !box.intersects(avoid))
                                && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                                && level.noCollision(e, box) && !level.containsAnyLiquid(box)) {
                            (pit(level, p, filled) ? pits : taken != null && taken.test(p) ? takens : fits).add(p);
                        }
                    }
                }
            }
            if (!fits.isEmpty()) {
                return nearest(e, fits);
            }
            if (onTaken == null && !takens.isEmpty()) {
                onTaken = nearest(e, takens);
            }
            if (pitted == null && !pits.isEmpty()) {
                pitted = nearest(e, pits);
            }
        }
        return onTaken != null ? onTaken : pitted;
    }

    /** The bottom centre of the cell of {@code cells} nearest {@code e}. */
    private static Vec3 nearest(Entity e, List<BlockPos> cells) {
        BlockPos best = cells.get(0);
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : cells) {
            double d = e.distanceToSqr(Vec3.atBottomCenterOf(p));
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return Vec3.atBottomCenterOf(best);
    }

    /**
     * A pit: every neighbour of {@code feet} stands 2 or more over it (the cell over its feet is solid all round), so a body in it can't get out,
     * and the cell {@code filled} (a block about to go in, or null) counts as solid.
     */
    public static boolean pit(ServerLevel level, BlockPos feet, @Nullable BlockPos filled) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = feet.relative(d).above();
            if (n.equals(filled)) {
                continue;
            }
            if (!level.isLoaded(n) || level.getBlockState(n).getCollisionShape(level, n).isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
