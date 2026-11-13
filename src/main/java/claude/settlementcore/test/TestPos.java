package claude.settlementcore.test;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;

/**
 * World positions back into a test's own, correctly. 26.1.2's {@code GameTestHelper#relativePos} turns by the test's rotation
 * turned a further 180 degrees, which is the inverse only for the quarter turns: at {@link Rotation#NONE}, every engine test's
 * rotation, it mirrors x and z through the test's origin, so (32, 5, 44) comes back as (-32, 5, -44) (the laptop's runs of
 * khazuk c8abbdf: a Shrine's dais looked for on the far side of the plot). {@code relativeVec} turns forwards, not back, so it is
 * wrong at the quarter turns instead. Use these for any position a test acts on; the helper's own two only in messages.
 */
public final class TestPos {

    private TestPos() {
    }

    /** {@code abs} in the test's own frame: the exact inverse of {@code h.absolutePos}. */
    public static BlockPos relative(GameTestHelper h, BlockPos abs) {
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        Rotation back = inverse(h.getTestRotation());
        return StructureTemplate.transform(abs, Mirror.NONE, back, origin).subtract(origin);
    }

    /** {@code abs} in the test's own frame: the exact inverse of {@code h.absoluteVec}. */
    public static Vec3 relative(GameTestHelper h, Vec3 abs) {
        BlockPos origin = h.absolutePos(BlockPos.ZERO);
        Rotation back = inverse(h.getTestRotation());
        return StructureTemplate.transform(abs, Mirror.NONE, back, origin).subtract(Vec3.atLowerCornerOf(origin));
    }

    static Rotation inverse(Rotation r) {
        return switch (r) {
            case CLOCKWISE_90 -> Rotation.COUNTERCLOCKWISE_90;
            case COUNTERCLOCKWISE_90 -> Rotation.CLOCKWISE_90;
            default -> r;
        };
    }
}
