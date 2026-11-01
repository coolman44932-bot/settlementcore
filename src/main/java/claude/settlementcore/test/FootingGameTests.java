package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.unit.Footing;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Whether a cell has a way out (core/unit/Footing#escapes), asked of the engine directly: the number of cells that is room enough, the open
 * sky, what one pick shares, and what a settler steps over. Muster, Brood and the upkeep's own tests ask it of the places a settler comes up
 * in. Their ground is the plot of MusterGameTests, laid at +360 over the test and cleared after.
 */
@GameTestHolder(Core.MODID)
@PrefixGameTestTemplate(false)
public class FootingGameTests {

    /**
     * A hollow shut in on every side has a way out only if it is room enough: a tunnel of {@link Footing#LIMIT} less one cells sealed at
     * both ends has none, from its first cell or its last, and one of {@link Footing#LIMIT} cells, or of one more, has.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aSealedHollowOfFewerCellsThanTheLimitHasNoWayOut(GameTestHelper h) {
        BlockPos middle = MusterGameTests.openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos at = middle.offset(-18, 3, 4);
            for (int n : new int[]{Footing.LIMIT - 1, Footing.LIMIT, Footing.LIMIT + 1}) {
                MusterGameTests.hollow(level, at, n, 1);
                boolean first = Footing.escapes(level, at, Footing.LIMIT);
                boolean last = Footing.escapes(level, at.offset(n - 1, 0, 0), Footing.LIMIT);
                h.assertTrue(first == n >= Footing.LIMIT && last == first, "a sealed tunnel of " + n + " cells (limit " + Footing.LIMIT + ") had "
                        + (first ? "" : "no ") + "way out from its first cell and " + (last ? "one" : "none") + " from its last");
                MusterGameTests.unhollow(level, at, n, 1);
            }
        } finally {
            MusterGameTests.clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * The limit counts the cells a body stands in, not the air over them (the review of 18f90e3: the flood counted every cell it walked, so a tall
     * hollow reached {@link Footing#LIMIT} on floating layers of air). A sealed hollow of 3 by 3 by 4, 4 by 3 by 3, 2 by 2 by 8 and 1 by 12 by 3 cells
     * has 24 or more cells of air a body fits in and fewer than 24 to stand in: from a cell on its floor, one in the air over it and the far corner it
     * has no way out, and with a wall of it opened (the control) it has.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aSealedTallHollowOfFewCellsToStandInHasNoWayOut(GameTestHelper h) {
        BlockPos middle = MusterGameTests.openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos at = middle.offset(-1, 3, 4);
            for (int[] size : MusterGameTests.TALL) {
                int w = size[0];
                int d = size[1];
                int high = size[2];
                String name = w + " by " + d + " by " + high;
                MusterGameTests.hollow(level, at, w, d, high);
                BlockPos far = at.offset(w - 1, 0, d - 1);
                boolean floor = Footing.escapes(level, at, Footing.LIMIT);
                boolean air = Footing.escapes(level, at.above(), Footing.LIMIT);
                boolean corner = Footing.escapes(level, far, Footing.LIMIT);
                h.assertTrue(!floor && !air && !corner, "a hollow of " + name + " cells, sealed, had a way out from its floor (" + floor + "), from the air over it ("
                        + air + ") and from its far corner (" + corner + "): " + w * d + " cells to stand in, the limit " + Footing.LIMIT);
                level.setBlock(at.west(), Blocks.AIR.defaultBlockState(), 2);                      // the control: a way out
                level.setBlock(at.west().above(), Blocks.AIR.defaultBlockState(), 2);
                h.assertTrue(Footing.escapes(level, at, Footing.LIMIT) && Footing.escapes(level, far, Footing.LIMIT),
                        "the hollow of " + name + " cells with a wall opened had no way out");
                MusterGameTests.unhollow(level, at, w, d, high);
            }
        } finally {
            MusterGameTests.clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * A cell under the open sky has a way out whatever shuts it in at its sides: the same pocket of one cell has none under a roof, and one
     * under the sky once the roof's cell over it is opened (a pit has no walls to climb, but it is no hollow).
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aCellUnderTheOpenSkyHasAWayOutThoughWalledAboutIt(GameTestHelper h) {
        BlockPos middle = MusterGameTests.openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos at = middle.offset(-1, 3, 4);
            MusterGameTests.hollow(level, at, 1, 1);
            h.assertTrue(!Footing.escapes(level, at, Footing.LIMIT), "a pocket of one cell under a roof had a way out");
            level.setBlock(at.above(2), Blocks.AIR.defaultBlockState(), 2);
            h.assertTrue(Footing.escapes(level, at, Footing.LIMIT), "a pocket of one cell open to the sky had none");
        } finally {
            MusterGameTests.clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * What one pick shares: in a hollow of 8 by 8 cells shut in on every side (more than the limit) every cell has a way out, and asked about
     * all 64 with one {@link Footing.Known} the floods look at a fraction of the cells they look at asked each by itself (24 each, 1536
     * in all), and a cell asked twice is not looked at again.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void theCandidatesOfOnePickShareWhatTheFloodsFound(GameTestHelper h) {
        BlockPos middle = MusterGameTests.openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos at = middle.offset(-4, 3, 4);
            MusterGameTests.hollow(level, at, 8, 8);
            Footing.Known together = new Footing.Known();
            int apart = 0;
            for (int dx = 0; dx < 8; dx++) {
                for (int dz = 0; dz < 8; dz++) {
                    BlockPos cell = at.offset(dx, 0, dz);
                    h.assertTrue(Footing.escapes(level, cell, Footing.LIMIT, together), "a cell of a hollow of 64 had no way out, at " + cell.toShortString());
                    Footing.Known alone = new Footing.Known();
                    h.assertTrue(Footing.escapes(level, cell, Footing.LIMIT, alone), "a cell of a hollow of 64 had no way out asked by itself, at " + cell.toShortString());
                    h.assertTrue(alone.looked() <= Footing.LIMIT, "a flood looked at " + alone.looked() + " cells, over the limit of " + Footing.LIMIT);
                    apart += alone.looked();
                }
            }
            h.assertTrue(together.looked() * 4 < apart, "the cells asked together were looked at " + together.looked() + " times, not a quarter of the " + apart
                    + " of the same asked apart");
            int looked = together.looked();
            Footing.escapes(level, at, Footing.LIMIT, together);
            h.assertTrue(together.looked() == looked, "a cell asked twice was looked at again");
        } finally {
            MusterGameTests.clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * What a settler steps over: a cell on a stone floor under a stone roof, with its four neighbours the same thing and open air beyond, is shut
     * in by a fence (1.5 high: no step) or a wall of two blocks, and not by a stair, a slab, a carpet or a block (a step of a block or less).
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aSettlerStepsOverABlockButNotAFenceOrAWallOfTwo(GameTestHelper h) {
        BlockPos middle = MusterGameTests.openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos at = middle.offset(-1, 3, 4);
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    level.setBlock(at.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);   // the floor
                    level.setBlock(at.offset(dx, 3, dz), Blocks.STONE.defaultBlockState(), 2);    // the roof, over the open air, so the cell is not under the sky
                }
            }
            h.assertTrue(Footing.escapes(level, at, Footing.LIMIT), "a cell on a floor of 7 by 7 under a roof had no way out");
            ring(level, at, Blocks.OAK_FENCE.defaultBlockState(), null);
            h.assertTrue(!Footing.escapes(level, at, Footing.LIMIT), "a cell ringed by fences had a way out");
            ring(level, at, Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState());
            h.assertTrue(!Footing.escapes(level, at, Footing.LIMIT), "a cell ringed by walls two blocks high had a way out");
            ring(level, at, Blocks.STONE.defaultBlockState(), null);
            h.assertTrue(Footing.escapes(level, at, Footing.LIMIT), "a cell ringed by walls a block high had none");
            ring(level, at, Blocks.STONE_STAIRS.defaultBlockState(), null);
            h.assertTrue(Footing.escapes(level, at, Footing.LIMIT), "a cell ringed by stairs had none");
            ring(level, at, Blocks.STONE_SLAB.defaultBlockState(), null);
            h.assertTrue(Footing.escapes(level, at, Footing.LIMIT), "a cell ringed by slabs had none");
            ring(level, at, Blocks.WHITE_CARPET.defaultBlockState(), null);
            h.assertTrue(Footing.escapes(level, at, Footing.LIMIT), "a cell ringed by carpets had none");
        } finally {
            MusterGameTests.clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * A settler walks through an open door, an open trapdoor and a ladder, and not through a closed door or a trapdoor shut over its head: they
     * have a collision shape as tall as a block (a plate against one side of the cell), and the flood read every one as a wall, so a room whose
     * only way out was an open door was judged shut in (the review of 18f90e3). A room of 3 by 3 cells (fewer than {@link Footing#LIMIT}) with a way
     * out in its west wall of two cells high only: an oak door, open, has one (the doorway has the open sky beyond it); the same door closed has none
     * (rats do not open doors); nor has a trapdoor shut at the head of the doorway (a plate over the cell's top); a ladder and an open trapdoor
     * in the doorway have one.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aSettlerPassesAnOpenDoorATrapdoorAndALadderButNotAClosedDoor(GameTestHelper h) {
        BlockPos middle = MusterGameTests.openGround(h);
        try {
            ServerLevel level = h.getLevel();
            BlockPos at = middle.offset(-1, 3, 4);
            MusterGameTests.hollow(level, at, 3, 3);
            BlockPos doorway = at.west();                                   // the wall cell in line with the room's first row, and the one over it
            BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.WEST);
            doorway(level, doorway, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(DoorBlock.OPEN, true),
                    door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER).setValue(DoorBlock.OPEN, true));
            h.assertTrue(Footing.escapes(level, at, Footing.LIMIT), "a room of 9 cells with an open door in its wall had no way out");
            h.assertTrue(Footing.escapes(level, at.offset(2, 0, 2), Footing.LIMIT), "a room of 9 cells with an open door in its wall had no way out from its far corner");
            doorway(level, doorway, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
            h.assertTrue(!Footing.escapes(level, at, Footing.LIMIT), "a room of 9 cells with its door shut had a way out");
            BlockState shut = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, Direction.WEST).setValue(TrapDoorBlock.HALF, Half.TOP);
            doorway(level, doorway, Blocks.AIR.defaultBlockState(), shut);
            h.assertTrue(!Footing.escapes(level, at, Footing.LIMIT), "a room of 9 cells with a trapdoor shut at the head of its doorway had a way out");
            BlockState open = Blocks.OAK_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, Direction.WEST).setValue(TrapDoorBlock.OPEN, true);
            doorway(level, doorway, open, open);
            h.assertTrue(Footing.escapes(level, at, Footing.LIMIT), "a room of 9 cells with an open trapdoor in its doorway had no way out");
            BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.EAST);
            doorway(level, doorway, ladder, ladder);
            h.assertTrue(Footing.escapes(level, at, Footing.LIMIT), "a room of 9 cells with a ladder in its doorway had no way out");
            doorway(level, doorway, Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState());
            h.assertTrue(!Footing.escapes(level, at, Footing.LIMIT), "a room of 9 cells with its doorway walled up had a way out");
        } finally {
            MusterGameTests.clearGround(h, middle);
        }
        h.succeed();
    }

    /** The doorway's two cells, {@code low} in the cell at {@code at} and {@code high} in the one over it. */
    private static void doorway(ServerLevel level, BlockPos at, BlockState low, BlockState high) {
        level.setBlock(at, low, 2);
        level.setBlock(at.above(), high, 2);
    }

    /** The four cells about {@code at} set to {@code low}, and (when {@code high} is not null) the four over them to {@code high}, else air. */
    private static void ring(ServerLevel level, BlockPos at, BlockState low, BlockState high) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            level.setBlock(at.relative(d), low, 2);
            level.setBlock(at.relative(d).above(), high == null ? Blocks.AIR.defaultBlockState() : high, 2);
        }
    }
}
