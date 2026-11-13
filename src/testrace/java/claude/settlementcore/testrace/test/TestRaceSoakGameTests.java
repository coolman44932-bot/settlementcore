package claude.settlementcore.testrace.test;

import claude.settlementcore.food.Fare;
import claude.settlementcore.room.PlanPlacement;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.test.GameTest;
import claude.settlementcore.test.GameTestHolder;
import claude.settlementcore.test.Soak;
import claude.settlementcore.testrace.Guard;
import claude.settlementcore.testrace.HeartBlockEntity;
import claude.settlementcore.testrace.TRRegistry;
import claude.settlementcore.testrace.TestRace;
import claude.settlementcore.testrace.TestUnit;
import claude.settlementcore.testrace.Worker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * The soak (2026-11-13): a test race settlement left to live for an hour of game time, sampled every minute ({@link Soak}), must not keep
 * growing anything or slow down. Its Heart is fed, its Hoard is kept, its people rise round by round, and every few minutes one walks home and
 * sinks back into the reserve and a guard dies, so the rounds, the roll, the reserve, the crews and the haul log keep turning over the whole
 * hour, as in a world left running.
 *
 * <p>Off unless asked: {@code ./gradlew runGameTestServer -Ponly=soak -Psoak} (an hour of game time; {@code -Psoak=<ticks>} for another
 * length), or {@code python tools/soak.py run}. Without it the test passes at once, so a full run costs nothing.
 */
@GameTestHolder(TestRace.MODID)
public class TestRaceSoakGameTests {

    /** The Heart on the arena's floor (smooth stone at y 0, open above). */
    private static final BlockPos HEART = new BlockPos(12, 1, 12);
    /** The Hoard's door: it runs east from here (x 2-6, y 1-3, z 2-6). */
    private static final BlockPos HOARD_DOOR = new BlockPos(2, 1, 4);
    /** The arena is 24 x 24: a wall three high round its edge keeps an hour's wandering on the plot. */
    private static final int SIZE = 24;
    /** Food points the Heart is kept at: rounds always have the makings, so they make people whenever there is room. */
    private static final int FED = 400;

    @GameTest(template = "arena", timeoutTicks = Soak.MAX_TICKS, batch = "soak")
    public static void aSettlementLeftRunningKeepsNothingGrowing(GameTestHelper h) {
        long total = Soak.ticks();
        if (total <= 0) {
            h.succeed();                                          // no -Psoak: the full run has no hour to spare
            return;
        }
        for (int i = 0; i < SIZE; i++) {
            for (int y = 1; y <= 3; y++) {
                h.setBlock(new BlockPos(i, y, 0), Blocks.STONE_BRICKS.defaultBlockState());
                h.setBlock(new BlockPos(i, y, SIZE - 1), Blocks.STONE_BRICKS.defaultBlockState());
                h.setBlock(new BlockPos(0, y, i), Blocks.STONE_BRICKS.defaultBlockState());
                h.setBlock(new BlockPos(SIZE - 1, y, i), Blocks.STONE_BRICKS.defaultBlockState());
            }
        }
        h.setBlock(HEART, TRRegistry.HEART.get().defaultBlockState());
        if (!(h.getLevel().getBlockEntity(h.absolutePos(HEART)) instanceof HeartBlockEntity heart)) {
            throw h.assertionException("no Heart's block entity at " + h.absolutePos(HEART));
        }
        heart.addFood(FED, Fare.COOKED, "the soak");
        Rooms.Room room = TestRaceCrewGameTests.hoard();
        PlanPlacement pl = PlanPlacement.compute(room, h.absolutePos(HOARD_DOOR), Direction.EAST);
        BoundingBox b = pl.box();
        h.getLevel().setBlockAndUpdate(new BlockPos(b.maxX(), b.minY() + 1, b.maxZ()), Blocks.BARREL.defaultBlockState());
        heart.addBuilding(new Building(room.id(), b, pl.door(), Direction.EAST), true);

        AABB plot = new AABB(h.absolutePos(BlockPos.ZERO)).expandTowards(SIZE, 10, SIZE).inflate(2);
        Soak soak = Soak.start(h, "testrace");
        soak.run(h, total, Soak.DEFAULT_EVERY, n -> {
            if (heart.isRemoved()) {
                throw h.assertionException("the Heart is gone at sample " + n);
            }
            int food = heart.food();
            if (food < FED) {
                heart.addFood(FED - food, Fare.COOKED, "the soak");
            }
            heart.addCurrency(5, "the soak");                     // the haul log turns over too
            heart.spendCurrency(3, "the soak");
            if (n % 3 == 0) {
                List<Worker> workers = h.getLevel().getEntitiesOfClass(Worker.class, plot, w -> w.isAlive() && !w.isHomeward());
                if (!workers.isEmpty()) {
                    workers.get(0).goHome();                      // walks to the Heart and sinks into the reserve
                }
            }
            if (n % 5 == 0) {
                List<Guard> guards = h.getLevel().getEntitiesOfClass(Guard.class, plot, TestUnit::isAlive);
                if (!guards.isEmpty()) {
                    guards.get(0).kill(h.getLevel());             // off the roll for good: the next round makes another
                }
            }
        });
    }
}
