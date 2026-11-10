package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.room.PlanPlacement;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.test.MusterSaveGameTests.Fake;
import claude.settlementcore.test.SettlementBlockEntityGameTests.TestSettlement;
import claude.settlementcore.work.Chore;
import claude.settlementcore.work.DigWork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Two things the user saw in a big hold's dig (2026-11-09), tested on the engine's own settler ({@link Fake}, a pig's body, 0.9 wide):
 * <ul>
 * <li>"they take a long time to fill in 1 block at 99%, only 1 person is in charge of the last block and they dont come": the last
 * batch was held by whichever worker asked first, wherever it stood, and no other worker could be handed it (DigJob#claimAtFace hands
 * out only unclaimed cells; a claim lives 600 ticks). A worker beside it now takes it over from a holder that is far off.</li>
 * <li>"dwarves while mining bump into each other and stop each other from working": two bodies overlapping push each other 0.05 a
 * tick (LivingEntity#pushEntities), which carried a digger out of the 1.8 blocks' reach of its cell, so it stopped swinging and walked
 * back; and the dig gave two diggers one stand cell. A crew settler at a chore is not pushable now, and stands are spread.</li>
 * </ul>
 */
@GameTestHolder(Core.MODID)
public class DigCrowdGameTests {

    private static DigWork begun(GameTestHelper h, TestSettlement t, ServerLevel level) {
        Rooms.Room room = DigWorkGameTests.hall();
        t.holds = true;
        BlockPos door = h.absolutePos(new BlockPos(10, 5, 20));
        PlanPlacement pl = PlanPlacement.compute(room, door, Direction.EAST);
        level.setBlockAndUpdate(door.west(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(door.west().above(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(door.west().below(), Blocks.STONE.defaultBlockState());
        String begun = t.works().dig(level, pl, true);
        h.assertTrue(begun == null, "the dig was refused: " + begun);
        return t.works().digs().get(0);
    }

    private static Fake settler(ServerLevel level, TestSettlement t, BlockPos at) {
        Fake f = Fake.at(level, t, at, null);
        f.setInvulnerable(true);                                 // (it may stand in the rock: it is only a body for the distances)
        return f;
    }

    /**
     * One cell left of a dug hall, held by a worker 40 blocks off (the first to ask, when the cell was handed out). A worker beside the cell
     * asks 3 s later and is handed it; at once it is not (a holder gets its few seconds). Before: the claim lives 600 ticks, nothing hands
     * the cell to another, and the settlement's own digging waits for 10 s without progress.
     */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "settlement")
    public static void aLastBatchHeldFarOffGoesToAWorkerBeside(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        DigWork d = begun(h, t, level);
        int total = d.job().taskCount();
        d.job().gnawInOrder(level, total - 1, new ArrayList<>());
        h.assertTrue(total - d.job().finishedCount() == 1, "the set-up left " + (total - d.job().finishedCount()) + " cells, not 1");
        BlockPos hallMid = h.absolutePos(new BlockPos(3, 6, 20));
        Fake far = settler(level, t, h.absolutePos(new BlockPos(42, 6, 42)));
        Fake near = settler(level, t, hallMid);
        Chore held = d.nextChore(level, far);
        h.assertTrue(held != null, "the far worker was handed no cell, so the set-up holds nothing");
        h.assertTrue(d.nextChore(level, near) == null, "a batch only just handed out was taken off its holder at once");
        h.startSequence().thenIdle(70).thenExecute(() -> {
            double toFar = Math.sqrt(far.distanceToSqr(held.at.getX() + 0.5, held.at.getY() + 0.5, held.at.getZ() + 0.5));
            Chore got = d.nextChore(level, near);
            h.assertTrue(got != null, "3 s on, the last cell is still held by a worker " + String.format("%.0f", toFar)
                    + " blocks from it, and the worker beside it (" + String.format("%.0f", Math.sqrt(near.distanceToSqr(held.at.getX() + 0.5,
                    held.at.getY() + 0.5, held.at.getZ() + 0.5))) + " blocks) is handed nothing");
            h.assertTrue(d.job().holds(near.getUUID()) && !d.job().holds(far.getUUID()), "the cell was not moved to the worker beside it");
            h.assertTrue(held.held != null && !held.held.getAsBoolean() && got.held != null && got.held.getAsBoolean(),
                    "the far worker's chore doesn't know its batch is gone (it would walk on to cells it no longer holds), or the new one's isn't held");
            far.discard();
            near.discard();
        }).thenSucceed();
    }

    /**
     * Five diggers asked for a batch in a hall half dug: no two are given one stand cell. Before: the stand is the nearest cell to the work,
     * the same for neighbouring batches, and nothing in the choice knew who else was standing there.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void diggersAreGivenStandsOfTheirOwn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        DigWork d = begun(h, t, level);
        d.job().gnawInOrder(level, d.job().taskCount() / 2, new ArrayList<>());
        BlockPos mid = h.absolutePos(new BlockPos(3, 6, 20));
        List<Fake> crew = new ArrayList<>();
        List<BlockPos> stands = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Fake f = settler(level, t, mid);
            crew.add(f);
            Chore c = d.nextChore(level, f);
            h.assertTrue(c != null, "digger " + i + " was handed no batch");
            stands.add(c.standAt);
        }
        for (int i = 0; i < stands.size(); i++) {
            for (int j = i + 1; j < stands.size(); j++) {
                h.assertTrue(stands.get(i) == null || !stands.get(i).equals(stands.get(j)),
                        "diggers " + i + " and " + j + " were given one stand cell " + stands.get(i) + ": all stands " + stands);
            }
        }
        crew.forEach(Fake::discard);
        h.succeed();
    }

    /**
     * The stand itself (the review of 95d4d7d: five batches may have five nearest stands anyway): one digger is handed a batch and its
     * stand; a second digger asked where to stand for the SAME cell is given another cell, still within reach of the work, and the
     * first digger asked again keeps its own. Without the stand's cost for a cell another digger has, both are given the one cell.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void aSecondDiggerAtOneCellStandsBesideTheFirst(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        DigWork d = begun(h, t, level);
        d.job().gnawInOrder(level, d.job().taskCount() / 2, new ArrayList<>());
        BlockPos mid = h.absolutePos(new BlockPos(3, 6, 20));
        Fake first = settler(level, t, mid);
        Fake second = settler(level, t, mid);
        Chore c = d.nextChore(level, first);
        h.assertTrue(c != null && c.standAt != null, "the first digger was handed no batch with a stand: " + (c == null ? null : c.standAt));
        BlockPos again = d.standForTests(level, c.at, first.getUUID());
        h.assertTrue(c.standAt.equals(again), "the first digger's own stand moved when it was asked again: " + c.standAt + " then " + again);
        BlockPos other = d.standForTests(level, c.at, second.getUUID());
        h.assertTrue(other != null && !other.equals(c.standAt), "a second digger at the cell " + c.at + " is stood in the first one's cell "
                + c.standAt + " (given " + other + ")");
        int across = Math.max(Math.abs(other.getX() - c.at.getX()), Math.abs(other.getZ() - c.at.getZ()));
        h.assertTrue(across <= 3, "the second digger's stand " + other + " is " + across + " across from the cell " + c.at + ": out of reach");
        first.discard();
        second.discard();
        h.succeed();
    }

    /**
     * Two crew settlers at a chore, standing half a block apart (bodies overlapping), do not move for 40 ticks; a pair with no chore beside
     * them, for the control, is pushed apart. Before: both pairs are, 0.05 a tick, and a digger shoved from its cell is out of reach of it.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void crewAtAChoreDoNotShoveEachOther(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        for (int x = 14; x <= 30; x++) {
            for (int z = 14; z <= 30; z++) {
                for (int y = 6; y <= 8; y++) {
                    level.setBlockAndUpdate(h.absolutePos(new BlockPos(x, y, z)), Blocks.AIR.defaultBlockState());
                }
            }
        }
        BlockPos a = h.absolutePos(new BlockPos(18, 6, 18));
        BlockPos b = h.absolutePos(new BlockPos(26, 6, 26));
        Fake[] work = {pig(level, t, a, 0.30), pig(level, t, a, 0.70)};
        Fake[] idle = {pig(level, t, b, 0.30), pig(level, t, b, 0.70)};
        for (Fake f : work) {
            f.crew = true;
            f.crewDoing = "digging out the Test Dug Hall";
        }
        h.startSequence().thenIdle(40).thenExecute(() -> {
            double sepWork = Math.hypot(work[0].getX() - work[1].getX(), work[0].getZ() - work[1].getZ());
            double sepIdle = Math.hypot(idle[0].getX() - idle[1].getX(), idle[0].getZ() - idle[1].getZ());
            h.assertTrue(sepIdle > 0.6, "the control pair with no chore was not pushed apart (" + String.format("%.2f", sepIdle)
                    + " from 0.40): the set-up shoves nobody, so it proves nothing");
            h.assertTrue(sepWork < 0.5, "two crew at a chore, 0.4 apart, were shoved to " + String.format("%.2f", sepWork)
                    + " apart in 40 ticks (the control pair with no chore: " + String.format("%.2f", sepIdle) + ")");
            for (Fake f : work) {
                f.discard();
            }
            for (Fake f : idle) {
                f.discard();
            }
        }).thenSucceed();
    }

    private static Fake pig(ServerLevel level, TestSettlement t, BlockPos cell, double dx) {
        Fake f = Fake.at(level, t, cell, null);
        f.setNoAi(false);                                        // (a mob with no AI is not moved by a push either)
        f.setInvulnerable(true);
        f.snapTo(cell.getX() + dx, cell.getY(), cell.getZ() + 0.5, 0, 0);
        return f;
    }
}
