package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.room.PlanPlacement;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.test.SettlementBlockEntityGameTests.TestSettlement;
import claude.settlementcore.work.MineWork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * An Ore Mine's veins across a save (the bug audit of 2026-11-04). A drift follows every ore that shows in its walls along its vein, and
 * the holes the vein leaves are the drift's own open ground: the cave check, which ends a drift rather than break into open ground it did
 * not dig, passes over them. They were not saved, nor the vein being followed: after a load the vein's holes beside the drift's next column
 * were taken for a cave, and the main drift was given up for good (done, which is saved), with the rest of its 40 blocks and every branch.
 *
 * <p>In the digsite (solid stone): a mine of the tests' own, its one drift running east from its mouth; iron ore in the drift's north wall
 * beside its 2nd and 3rd columns. Its night's work ({@link MineWork#nightsWork}, the crew of one: a column or a vein cell for every 3 seconds)
 * gnaws the columns and follows the vein a step at a time; between the steps the mine is saved and loaded into a new one, as a world is.
 */
@GameTestHolder(Core.MODID)
public class MineVeinGameTests {

    /** Fails with {@code why} unless {@code ok}; the words made only on failing (a mine's report line looks at its drifts' next columns). */
    static void expect(GameTestHelper h, boolean ok, Supplier<String> why) {
        if (!ok) {
            h.assertTrue(false, why.get());
        }
    }

    /** A mine of the tests' own: 3 x 3 x 3, door (0, 1, 1), its drift's mouth at (2, 1, 1), the drift running out of its east wall. */
    static Rooms.Room mineRoom() {
        return new Rooms.Room("mine_vein_test", "Test Ore Mine", 0, Rooms.Function.OREMINE, "A mine of the vein tests, registered nowhere.",
                3, 3, 3, 0, 1, 1, List.of(), List.of(), List.of(new Rooms.Station("drift", 2, 1, 1, Direction.EAST)));
    }

    /** A mine with its door at {@code door} of the test, its room hollowed out of the stone; ore beside its drift's 2nd and 3rd columns. */
    record Mine(TestSettlement heart, Building building, PlanPlacement placement, BlockPos mouth) {
        static Mine at(GameTestHelper h, TestSettlement t, BlockPos door) {
            ServerLevel level = h.getLevel();
            PlanPlacement pl = PlanPlacement.compute(mineRoom(), h.absolutePos(door), Direction.EAST);
            Building b = new Building("mine_vein_test", pl.box(), pl.door(), Direction.EAST);
            for (BlockPos p : BlockPos.betweenClosed(pl.box().minX(), pl.box().minY() + 1, pl.box().minZ(), pl.box().maxX(), pl.box().maxY(), pl.box().maxZ())) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            }
            MineWork w = new MineWork(t, b, pl);
            BlockPos mouth = w.driftMouths().get(0);
            for (int i = 1; i <= 6; i++) {                       // the drift's first columns, their walls, floor and roof: stone
                for (BlockPos p : BlockPos.betweenClosed(mouth.east(i).offset(0, -1, -1), mouth.east(i).offset(0, 2, 1))) {
                    level.setBlockAndUpdate(p, Blocks.STONE.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(mouth.east(2).north(), Blocks.IRON_ORE.defaultBlockState());
            level.setBlockAndUpdate(mouth.east(3).north(), Blocks.IRON_ORE.defaultBlockState());
            return new Mine(t, b, pl, mouth);
        }

        MineWork work() {
            return new MineWork(heart, building, placement);
        }

        /** {@code from}'s state saved, and loaded into a new mine of the same room: a world saved and loaded again. */
        MineWork reloaded(CompoundTag saved) {
            MineWork w = work();
            w.load(saved);
            return w;
        }

        /** {@code columns} steps of the mine's night: a column of a drift, or a cell of a vein, each. */
        static void steps(ServerLevel level, MineWork w, int columns) {
            List<Component> report = new ArrayList<>();
            w.nightsWork(level, columns * 3, report);             // a crew of one: floor(seconds * 0.35) steps
        }

        BlockPos column(int i) {
            return mouth.east(i);
        }
    }

    /**
     * The audit's case: the vein beside the drift followed (both its cells gnawed out, one of them beside the next column), the mine saved
     * and loaded, and the drift goes on through its 3rd column, not ended as a cave at the vein's hole.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aDriftBesideAFollowedVeinGoesOnAfterALoad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        try {
            Mine m = Mine.at(h, t, new BlockPos(4, 5, 8));
            MineWork w = m.work();
            Mine.steps(level, w, 2);                             // its 1st and 2nd columns; the ore beside the 2nd noted
            Mine.steps(level, w, 2);                             // the vein followed: its two cells
            expect(h, w.driftNext(0) == 2 && w.veinWaiting(0) == 0 && w.veinGnawed(0) == 2
                            && level.getBlockState(m.column(3).north()).isAir() && level.getBlockState(m.column(2).north()).isAir(),
                    () -> "the mine did not get the vein followed to beside its 3rd column: next " + w.driftNext(0) + ", " + w.veinWaiting(0) + " waiting, "
                            + w.veinGnawed(0) + " gnawed; " + w.describeLine(level));
            MineWork loaded = m.reloaded(w.save());
            Mine.steps(level, loaded, 1);
            expect(h, !loaded.driftDone(0), () -> "after the load the drift was ended at its 3rd column, the vein's hole beside it taken for a cave ("
                    + loaded.veinGnawed(0) + " of the vein's 2 holes known): " + loaded.describeLine(level));
            expect(h, loaded.driftNext(0) == 3 && level.getBlockState(m.column(3)).isAir() && level.getBlockState(m.column(3).above()).isAir(),
                    () -> "after the load the drift did not gnaw its 3rd column: " + loaded.describeLine(level));
            expect(h, loaded.veinGnawed(0) == 2, () -> "the vein's holes were not kept across the save: " + loaded.veinGnawed(0) + " of 2");
        } finally {
            t.setRemoved();
        }
        h.succeed();
    }

    /**
     * The vein being followed is saved too: saved with the ore beside the 2nd column noted and not yet gnawed, the loaded mine still has
     * it waiting, follows it (both cells), and its drift goes on past them after a second load.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void theVeinBeingFollowedIsKeptAcrossALoad(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        try {
            Mine m = Mine.at(h, t, new BlockPos(4, 5, 20));
            MineWork w = m.work();
            Mine.steps(level, w, 2);
            h.assertTrue(w.driftNext(0) == 2 && w.veinWaiting(0) == 1, "the ore beside the 2nd column was not noted: " + w.veinWaiting(0) + " waiting");
            MineWork loaded = m.reloaded(w.save());
            h.assertTrue(loaded.veinWaiting(0) == 1, "the vein waiting to be followed was not kept across the save: " + loaded.veinWaiting(0) + " of 1");
            Mine.steps(level, loaded, 2);
            h.assertTrue(loaded.veinGnawed(0) == 2 && loaded.driftNext(0) == 2 && level.getBlockState(m.column(3).north()).isAir(),
                    "the loaded mine did not follow the vein: " + loaded.veinGnawed(0) + " gnawed, next " + loaded.driftNext(0));
            MineWork again = m.reloaded(loaded.save());
            Mine.steps(level, again, 1);
            expect(h, !again.driftDone(0) && again.driftNext(0) == 3 && level.getBlockState(m.column(3)).isAir(),
                    () -> "after the second load the drift did not go on through its 3rd column: " + again.describeLine(level));
        } finally {
            t.setRemoved();
        }
        h.succeed();
    }

    /**
     * A mine saved before its vein holes were (a save with no "veins"): the holes are forgotten, and its drift takes a small pocket of air
     * bordering its own open ground for its own, so it goes on past the vein. But not anything bigger, nor a pocket that does not border the
     * drift: a cave of 125 cells beside the next column (bordering the drift too) ends it, and so does a sealed hole beside it.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void anOldSaveTakesItsVeinHolesForItsOwnButACaveStillEndsTheDrift(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        try {
            // the vein followed, saved as an old jar saved it (no "veins")
            Mine m = Mine.at(h, t, new BlockPos(4, 5, 4));
            MineWork w = m.work();
            Mine.steps(level, w, 4);
            CompoundTag old = w.save();
            old.remove("veins");
            old.remove("veins_unrecorded");
            MineWork loaded = m.reloaded(old);
            h.assertTrue(loaded.veinGnawed(0) == 0 && level.getBlockState(m.column(3).north()).isAir(), "the old save's case is not set up: "
                    + loaded.veinGnawed(0) + " gnawed");
            Mine.steps(level, loaded, 1);
            expect(h, !loaded.driftDone(0) && loaded.driftNext(0) == 3, () -> "a mine saved before its vein holes were ended its drift at the vein's hole: "
                    + loaded.describeLine(level));
            // and it stays tolerant once saved again (its old holes are still unknown)
            h.assertTrue(loaded.save().getBooleanOr("veins_unrecorded", false), "a mine loaded from an old save forgot that it was, when saved again");

            // a cave beside the next column (5 x 5 x 5, bordering the drift's 2nd column): too big for a vein's holes
            Mine big = Mine.at(h, t, new BlockPos(4, 5, 16));
            MineWork b = big.work();
            Mine.steps(level, b, 2);
            BlockPos n2 = big.column(2).north();
            for (BlockPos p : BlockPos.betweenClosed(n2.offset(0, 0, -4), n2.offset(4, 4, 0))) {
                level.setBlockAndUpdate(p, Blocks.AIR.defaultBlockState());
            }
            CompoundTag oldB = b.save();
            oldB.remove("veins");
            MineWork bl = big.reloaded(oldB);
            Mine.steps(level, bl, 1);
            expect(h, bl.driftDone(0) && bl.driftNext(0) == 2 && !level.getBlockState(big.column(3)).isAir(),
                    () -> "a drift of an old save broke into a cave of 125 cells beside its 3rd column: " + bl.describeLine(level));

            // a sealed hole beside the next column, touching nothing the drift has open
            Mine sealed = Mine.at(h, t, new BlockPos(4, 5, 30));
            MineWork s = sealed.work();
            Mine.steps(level, s, 2);
            level.setBlockAndUpdate(sealed.column(2).north(), Blocks.STONE.defaultBlockState());     // the ore beside the 2nd column: rock
            level.setBlockAndUpdate(sealed.column(3).north(), Blocks.AIR.defaultBlockState());       // and a hole beside the 3rd
            CompoundTag oldS = s.save();
            oldS.remove("veins");
            MineWork sl = sealed.reloaded(oldS);
            Mine.steps(level, sl, 1);
            expect(h, sl.driftDone(0) && !level.getBlockState(sealed.column(3)).isAir(),
                    () -> "a drift of an old save broke into a sealed hole that borders nothing it has open: " + sl.describeLine(level));
        } finally {
            t.setRemoved();
        }
        h.succeed();
    }
}
