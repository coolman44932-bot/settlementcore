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

    /** The Heart on the arena's floor (tools/gen_testrace_arena.py: smooth stone at y 0, open above; the digsite is solid stone). */
    private static final BlockPos HEART = new BlockPos(12, 1, 12);

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
    @GameTest(template = "arena", timeoutTicks = 100, batch = "testrace")
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
    @GameTest(template = "arena", timeoutTicks = 400, batch = "testrace")
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

    /**
     * A round calls the reserve up, not only what it made: a Heart at its reserve cap (24 in all) with 16 workers and 8 guards below, no one up and
     * food to spare, makes nobody (Brood#room is 0) and, before 2026-11-13, brought nobody up either (it brought up only what the round had made), so
     * a settlement whose people had sunk home stayed empty for good: the hour's soak drained to 0 up and 23 below. The round must bring up 8 (the
     * people cap), two workers to a guard (5 and 3), and leave 11 workers and 5 guards below.
     */
    @GameTest(template = "arena", timeoutTicks = 100, batch = "testrace")
    public static void aRoundCallsTheReserveUpToTheCap(GameTestHelper h) {
        HeartBlockEntity heart = heart(h);
        heart.addToReserve(16, 8);
        h.assertTrue(heart.peopleCount() == 0 && heart.population() == 24, "set up: " + heart.peopleCount() + " up and a population of "
                + heart.population() + ", not 0 and 24");
        heart.roundForTests(h.getLevel());
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(near(h, Worker.class).size() == 5 && near(h, Guard.class).size() == 3, "up after the round: "
                        + near(h, Worker.class).size() + " workers and " + near(h, Guard.class).size() + " guards, not 5 and 3 (people "
                        + heart.peopleCount() + ", reserve " + heart.reserveWorkers() + " workers and " + heart.reserveSoldiers() + " guards)"))
                .thenExecute(() -> h.assertTrue(heart.peopleCount() == 8 && heart.reserveWorkers() == 11 && heart.reserveSoldiers() == 5,
                        "the roll has " + heart.peopleCount() + " and the reserve " + heart.reserveWorkers() + " workers and "
                        + heart.reserveSoldiers() + " guards, not 8 and 11 and 5"))
                .thenSucceed();
    }

    /**
     * A guard falls on a husk that comes near (a foe by the engine's word), hurts it, plays its attack clip, and its swing runs out after.
     * Hostiles need the Normal difficulty: the batch sets it.
     */
    @GameTest(template = "arena", timeoutTicks = 400, batch = "testracehostile")
    public static void aGuardFallsOnAFoe(GameTestHelper h) {
        HeartBlockEntity heart = heart(h);
        heart.roundForTests(h.getLevel());
        Husk[] foe = new Husk[1];
        Guard[] guard = new Guard[1];
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(near(h, Guard.class).size() == 1, "the round's guard is not up"))
                .thenExecute(() -> {
                    guard[0] = near(h, Guard.class).get(0);
                    foe[0] = h.spawn(EntityType.HUSK, HEART.offset(5, 0, 0));
                    foe[0].setPersistenceRequired();
                })
                // the guard's own blow (the husk's health alone once passed at its first tick: something else had hurt it)
                .thenWaitUntil(() -> {
                    Guard g = guard[0];
                    h.assertTrue(g.attacksForTests > 0, "the guard has landed no blow: " + (g.isRemoved() ? "it is gone (" + g.getRemovalReason() + ", burrowing "
                            + g.isBurrowing() + ", homeward " + g.isHomeward() + ", dead " + g.isDeadOrDying() + ")" : "its target is " + g.getTarget() + ", goals "
                            + g.runningGoals() + ", at " + g.blockPosition()) + "; the husk at " + foe[0].getHealth() + "/" + foe[0].getMaxHealth()
                            + ", last hurt by " + foe[0].getLastDamageSource());
                })
                .thenExecute(() -> {
                    h.assertTrue(foe[0].isDeadOrDying() || foe[0].getHealth() < foe[0].getMaxHealth(), "the guard's blow left the husk unhurt");
                    foe[0].discard();
                })
                // its arm's swing runs out (a settler's swing timer once never ticked, so it swung for good: the review of 737e467)
                .thenWaitUntil(() -> h.assertTrue(!guard[0].swinging, "the guard is still swinging long after its blow"))
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
