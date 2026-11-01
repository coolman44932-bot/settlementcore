package claude.settlementcore.testrace.test;

import claude.settlementcore.dev.BridgeHooks;
import claude.settlementcore.food.Fare;
import claude.settlementcore.test.GameTest;
import claude.settlementcore.test.GameTestHolder;
import claude.settlementcore.testrace.HeartBlockEntity;
import claude.settlementcore.testrace.TRRegistry;
import claude.settlementcore.testrace.TestRace;
import claude.settlementcore.testrace.Worker;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.Map;

/**
 * What the engine tells devbridge (dev/BridgeHooks), read off a real race: a settler's state names its mode and settlement, any
 * other mob gets nothing, and the settlement route finds a Heart by its position and counts its people.
 */
@GameTestHolder(TestRace.MODID)
public class TestRaceBridgeGameTests {

    private static final BlockPos HEART = new BlockPos(12, 1, 12);

    @GameTest(template = "arena", timeoutTicks = 100, batch = "testrace")
    public static void theBridgeIsToldASettlersStateAndItsSettlement(GameTestHelper h) {
        h.setBlock(HEART, TRRegistry.HEART.get().defaultBlockState());
        HeartBlockEntity heart = (HeartBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(HEART));
        heart.addFood(200, Fare.COOKED, "the test");
        heart.roundForTests(h.getLevel());
        var pig = h.spawn(EntityType.PIG, new BlockPos(3, 1, 3));
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(workers(h).size() == 2, "the round's workers are not up"))
                .thenExecute(() -> {
                    Map<String, Object> s = BridgeHooks.settler(workers(h).get(0));
                    h.assertTrue(s != null && "WORK".equals(s.get("mode")) && Boolean.TRUE.equals(s.get("worker")),
                            "a worker's state should say mode WORK and worker: " + s);
                    h.assertTrue(String.valueOf(s.get("settlement")).startsWith(heart.settlementName() + " at "),
                            "its settlement should be " + heart.settlementName() + ": " + s.get("settlement"));
                    h.assertTrue(BridgeHooks.settler(pig) == null, "a pig got a settler's state");
                    BlockPos at = h.absolutePos(HEART);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> st = (Map<String, Object>) BridgeHooks.settlement(Map.of("pos", List.of(at.getX(), at.getY(), at.getZ()),
                            "_dimension", h.getLevel().dimension().identifier().toString()));
                    h.assertTrue(heart.settlementName().equals(st.get("name")), "the route found " + st.get("name") + ", not " + heart.settlementName());
                    h.assertTrue(String.valueOf(st.get("people")).startsWith(heart.peopleCount() + "/"), "the route counts " + st.get("people")
                            + ", the Heart " + heart.peopleCount());
                })
                .thenSucceed();
    }

    private static List<Worker> workers(GameTestHelper h) {
        return h.getLevel().getEntitiesOfClass(Worker.class, new AABB(h.absolutePos(HEART)).inflate(8));
    }
}
