package claude.settlementcore.testrace.test;

import claude.settlementcore.food.Fare;
import claude.settlementcore.test.AfterBatch;
import claude.settlementcore.test.BeforeBatch;
import claude.settlementcore.test.GameTest;
import claude.settlementcore.test.GameTestHolder;
import claude.settlementcore.testrace.Guard;
import claude.settlementcore.testrace.HeartBlockEntity;
import claude.settlementcore.testrace.TRRegistry;
import claude.settlementcore.testrace.TestRace;
import claude.settlementcore.testrace.TestUnit;
import claude.settlementcore.testrace.Worker;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * The test race on the engine, end to end in a real level: the Heart makes its people out of food and brings them up, a worker sent home
 * sinks back into the reserve, and a guard falls on a foe. The engine's own tests stand in for a race with a stub; these are a race.
 */
@GameTestHolder(TestRace.MODID)
public class TestRaceGameTests {

    private static final BlockPos HEART = new BlockPos(20, 2, 20);

    private static HeartBlockEntity heart(GameTestHelper h) {
        h.setBlock(HEART, TRRegistry.HEART.get().defaultBlockState());
        if (!(h.getLevel().getBlockEntity(h.absolutePos(HEART)) instanceof HeartBlockEntity heart)) {
            throw h.assertionException("no Heart's block entity at " + h.absolutePos(HEART));
        }
        heart.addFood(200, Fare.COOKED, "the test");
        return heart;
    }

    private static <T extends TestUnit> List<T> near(GameTestHelper h, Class<T> kind) {
        return h.getLevel().getEntitiesOfClass(kind, new AABB(h.absolutePos(HEART)).inflate(8));
    }

    /** A round makes two workers and a guard out of the Heart's food (4, 4 and 6 points), and, with room for 8 up, brings all three up. */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "testrace")
    public static void aHeartRaisesItsPeopleFromFood(GameTestHelper h) {
        HeartBlockEntity heart = heart(h);
        int food = heart.food();
        heart.roundForTests(h.getLevel());
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(near(h, Worker.class).size() == 2 && near(h, Guard.class).size() == 1,
                        "up beside the Heart: " + near(h, Worker.class).size() + " workers and " + near(h, Guard.class).size() + " guards, not 2 and 1"))
                .thenExecute(() -> {
                    h.assertTrue(heart.peopleCount() == 3 && heart.reserve() == 0, "the roll has " + heart.peopleCount() + " and the reserve "
                            + heart.reserve() + ", not 3 and 0");
                    h.assertTrue(food - heart.food() == 14, "the round cost " + (food - heart.food()) + " food points, not 4 + 4 + 6");
                    for (TestUnit u : near(h, TestUnit.class)) {
                        h.assertTrue(u.settlement() == heart, u.getName().getString() + " is not bound to the Heart");
                    }
                })
                .thenSucceed();
    }

    /** Sent home, a worker walks to the Heart and sinks into the ground: off the roll and into the reserve, and gone from the world. */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "testrace")
    public static void aWorkerSentHomeSinksBackIntoTheReserve(GameTestHelper h) {
        HeartBlockEntity heart = heart(h);
        heart.roundForTests(h.getLevel());
        Worker[] sent = new Worker[1];
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(near(h, Worker.class).size() == 2, "the round's workers are not up"))
                .thenExecute(() -> {
                    sent[0] = near(h, Worker.class).get(0);
                    sent[0].goHome();
                })
                .thenWaitUntil(() -> h.assertTrue(sent[0].isRemoved(), "the worker sent home is still about: " + sent[0].runningGoals()))
                .thenExecute(() -> h.assertTrue(heart.reserveWorkers() == 1 && heart.peopleCount() == 2 && !heart.hasPerson(sent[0].getUUID()),
                        "after it sank: " + heart.reserveWorkers() + " workers below and " + heart.peopleCount() + " up, not 1 and 2"))
                .thenSucceed();
    }

    /** A guard falls on a husk that comes near (a foe by the engine's word) and hurts it. Hostiles need the Normal difficulty: the batch sets it. */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "testracehostile")
    public static void aGuardFallsOnAFoe(GameTestHelper h) {
        HeartBlockEntity heart = heart(h);
        heart.roundForTests(h.getLevel());
        Husk[] foe = new Husk[1];
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(near(h, Guard.class).size() == 1, "the round's guard is not up"))
                .thenExecute(() -> {
                    foe[0] = h.spawn(EntityType.HUSK, HEART.offset(5, 0, 0));
                    foe[0].setPersistenceRequired();
                })
                .thenWaitUntil(() -> h.assertTrue(foe[0].isDeadOrDying() || foe[0].getHealth() < foe[0].getMaxHealth(),
                        "the husk is unhurt; the guard's target is " + near(h, Guard.class).get(0).getTarget()))
                .thenExecute(() -> foe[0].discard())
                .thenSucceed();
    }

    private static Difficulty was = Difficulty.PEACEFUL;

    @BeforeBatch(batch = "testracehostile")
    public static void hostileBegins(ServerLevel level) {
        was = level.getServer().getWorldData().getDifficulty();
        level.getServer().setDifficulty(Difficulty.NORMAL, true);
    }

    @AfterBatch(batch = "testracehostile")
    public static void hostileEnds(ServerLevel level) {
        level.getServer().setDifficulty(was, true);
    }
}
