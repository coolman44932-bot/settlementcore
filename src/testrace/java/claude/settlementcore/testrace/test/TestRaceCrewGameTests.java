package claude.settlementcore.testrace.test;

import claude.settlementcore.Core;
import claude.settlementcore.room.PlanPlacement;
import claude.settlementcore.room.RoomTemplates;
import claude.settlementcore.room.Rooms;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.test.GameTest;
import claude.settlementcore.test.GameTestHolder;
import claude.settlementcore.test.SitingGameTests;
import claude.settlementcore.testrace.HeartBlockEntity;
import claude.settlementcore.testrace.TRRegistry;
import claude.settlementcore.testrace.TestRace;
import claude.settlementcore.unit.SettlerMob;
import claude.settlementcore.work.SiteWork;
import claude.settlementcore.work.Works;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The engine's crews with the test race's real settlers: what a crew settler carries goes back into the settlement when it is taken off its work
 * without dying (recalled, discarded, its chunk unloaded), and a reserve too short for every room's want is shared, so a site begun after a dig
 * gets builders.
 */
@GameTestHolder(TestRace.MODID)
public class TestRaceCrewGameTests {

    /** The Heart on the arena's floor (smooth stone at y 0, open above). */
    private static final BlockPos HEART = new BlockPos(12, 1, 12);
    /** The test Hoard's door: it runs east from here, x 2-6, y 0-2, z 2-6. */
    private static final BlockPos HOARD_DOOR = new BlockPos(2, 1, 4);
    static final String HOARD = "testrace_crew_hoard";
    static final String HALL = "testrace_share_hall";

    /** A 5 x 3 x 5 Hoard with no template of its own (its work reads only the containers in its box); door (0, 1, 2). */
    static synchronized Rooms.Room hoard() {
        if (Rooms.get(HOARD) == null) {
            Rooms.Room r = new Rooms.Room(HOARD, "Test Crew Hoard", 0, Rooms.Function.HOARD, "A Hoard of the test race's crew tests.", 5, 3, 5, 0, 1, 2,
                    List.of(), List.of(), List.of());
            Rooms.register(Core.NAMESPACE, new Rooms.Catalog(List.of(), List.of(r), List.of(), Set.of(), 0));
        }
        return Rooms.get(HOARD);
    }

    /** A 9 x 6 x 9 hall dug out of rock (486 cells: a dig that wants 13 diggers): its floor of stone bricks, open above; door (0, 1, 4). */
    static synchronized Rooms.Room hall() {
        if (!RoomTemplates.has(HALL)) {
            List<RoomTemplates.Entry> cells = new ArrayList<>();
            for (int y = 0; y < 6; y++) {
                for (int z = 0; z < 9; z++) {
                    for (int x = 0; x < 9; x++) {
                        BlockState s = y == 0 ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState();
                        cells.add(new RoomTemplates.Entry(new BlockPos(x, y, z), s, null));
                    }
                }
            }
            RoomTemplates.put(HALL, cells);
        }
        if (Rooms.get(HALL) == null) {
            Rooms.Room r = new Rooms.Room(HALL, "Test Share Hall", 0, Rooms.Function.HALL, "A dug hall of the test race's crew tests.", 9, 6, 9, 0, 1, 4,
                    List.of(), List.of(), List.of());
            Rooms.register(Core.NAMESPACE, new Rooms.Catalog(List.of(), List.of(r), List.of(), Set.of(), 0));
        }
        return Rooms.get(HALL);
    }

    private static HeartBlockEntity heart(GameTestHelper h, BlockPos at) {
        h.setBlock(at, TRRegistry.HEART.get().defaultBlockState());
        if (!(h.getLevel().getBlockEntity(h.absolutePos(at)) instanceof HeartBlockEntity heart)) {
            throw h.assertionException("no Heart's block entity at " + h.absolutePos(at));
        }
        return heart;                                            // no food: its rounds raise nobody, so the reserve is only what a test gives it
    }

    /** The Heart, its Hoard with one barrel, and one worker in the reserve to keep it: the barrel's place. */
    private static BlockPos hoardWithKeeper(GameTestHelper h, HeartBlockEntity heart) {
        Rooms.Room room = hoard();
        PlanPlacement pl = PlanPlacement.compute(room, h.absolutePos(HOARD_DOOR), Direction.EAST);
        BoundingBox b = pl.box();
        BlockPos barrel = new BlockPos(b.maxX(), b.minY() + 1, b.maxZ());
        h.getLevel().setBlockAndUpdate(barrel, Blocks.BARREL.defaultBlockState());
        heart.addBuilding(new Building(room.id(), b, pl.door(), Direction.EAST), true);
        heart.addToReserve(1, 0);
        return barrel;
    }

    private static int diamonds(GameTestHelper h, BlockPos barrel) {
        int n = 0;
        if (h.getLevel().getBlockEntity(barrel) instanceof Container c) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                if (c.getItem(i).is(Items.DIAMOND)) {
                    n += c.getItem(i).getCount();
                }
            }
        }
        return n;
    }

    private static int lying(GameTestHelper h) {
        AABB all = new AABB(h.absolutePos(BlockPos.ZERO)).expandTowards(24, 10, 24).inflate(4);
        return h.getLevel().getEntitiesOfClass(ItemEntity.class, all, e -> e.getItem().is(Items.DIAMOND)).stream().mapToInt(e -> e.getItem().getCount()).sum();
    }

    private static int onPile(HeartBlockEntity heart) {
        return heart.spoils().stream().filter(s -> s.is(Items.DIAMOND)).mapToInt(ItemStack::getCount).sum();
    }

    /** The Hoard's keeper, once it is up. */
    private static SettlerMob keeper(GameTestHelper h, HeartBlockEntity heart) {
        List<SettlerMob> crew = heart.works().crewOf(h.getLevel(), h.absolutePos(HOARD_DOOR));
        h.assertTrue(crew.size() == 1, "the Hoard has " + crew.size() + " keepers up, not 1");
        return crew.get(0);
    }

    /**
     * The Hoard-keeper carrying a 64-stack when the Works recall every crew (Works#recall, as Works#dismiss does when a room is gone and Works#second
     * when a crew settler has strayed): the stack goes into the Hoard's barrel, the keeper's hand is empty and it is back in the reserve. Before, it
     * was discarded with the stack in its hand, and the stack was gone.
     */
    @GameTest(template = "arena", timeoutTicks = 200, batch = "testrace")
    public static void aRecalledCrewSettlersLoadGoesIntoTheHoard(GameTestHelper h) {
        HeartBlockEntity heart = heart(h, HEART);
        BlockPos barrel = hoardWithKeeper(h, heart);
        ServerLevel level = h.getLevel();
        h.startSequence()
                .thenWaitUntil(() -> keeper(h, heart))
                .thenExecute(() -> {
                    SettlerMob k = keeper(h, heart);
                    k.carry(new ItemStack(Items.DIAMOND, 64));
                    heart.works().recall(level);
                    h.assertTrue(k.isRemoved() && k.getMainHandItem().isEmpty(), "the recalled keeper is still about, or still holds "
                            + k.getMainHandItem());
                    h.assertTrue(diamonds(h, barrel) == 64 && onPile(heart) == 0 && lying(h) == 0, "the keeper's 64 diamonds: "
                            + diamonds(h, barrel) + " in the Hoard, " + onPile(heart) + " on the spoils pile, " + lying(h) + " on the ground, not all 64 in the Hoard");
                    h.assertTrue(heart.reserveWorkers() == 1, "the keeper isn't back in the reserve: " + heart.reserveWorkers());
                })
                .thenSucceed();
    }

    /**
     * A crew settler discarded by anything else (a race's own discard, its sinking home): the engine's hook (SettlerMob#onRemovedFromLevel) puts its
     * load into the Hoard, once. Before, only a death dropped it.
     */
    @GameTest(template = "arena", timeoutTicks = 200, batch = "testrace")
    public static void aDiscardedCrewSettlersLoadGoesIntoTheHoard(GameTestHelper h) {
        HeartBlockEntity heart = heart(h, HEART);
        BlockPos barrel = hoardWithKeeper(h, heart);
        SettlerMob[] k = new SettlerMob[1];
        h.startSequence()
                .thenWaitUntil(() -> keeper(h, heart))
                .thenExecute(() -> {
                    k[0] = keeper(h, heart);
                    k[0].carry(new ItemStack(Items.DIAMOND, 64));
                    k[0].discard();
                    h.assertTrue(k[0].getMainHandItem().isEmpty(), "the discarded keeper still holds " + k[0].getMainHandItem());
                    h.assertTrue(diamonds(h, barrel) == 64 && onPile(heart) == 0 && lying(h) == 0, "the keeper's 64 diamonds: "
                            + diamonds(h, barrel) + " in the Hoard, " + onPile(heart) + " on the spoils pile, " + lying(h) + " on the ground, not all 64 in the Hoard");
                })
                .thenWaitUntil(() -> h.assertTrue(!heart.works().isCrew(k[0].getUUID()), "the Works still count the discarded keeper as crew"))
                .thenExecute(() -> h.assertTrue(diamonds(h, barrel) == 64, "the Hoard has " + diamonds(h, barrel) + " diamonds, not the 64 once"))
                .thenSucceed();
    }

    /**
     * The keeper's chunk unloading (or the server stopping): on 26.1 an unsaved settler is not removed then, it stops being tracked, with no removal
     * reason. Its load leaves its hand at once and is owed to the Heart (nothing in the world is touched while the chunk's entities are walked over),
     * then put into the Hoard within the second. The hook is called here as the level calls it for such a settler.
     */
    @GameTest(template = "arena", timeoutTicks = 200, batch = "testrace")
    public static void anUnloadedCrewSettlersLoadIsOwedThenPutAway(GameTestHelper h) {
        HeartBlockEntity heart = heart(h, HEART);
        BlockPos barrel = hoardWithKeeper(h, heart);
        ServerLevel level = h.getLevel();
        h.startSequence()
                .thenWaitUntil(() -> keeper(h, heart))
                .thenExecute(() -> {
                    SettlerMob k = keeper(h, heart);
                    k.carry(new ItemStack(Items.DIAMOND, 64));
                    h.assertTrue(k.getRemovalReason() == null && !k.shouldBeSaved(), "the keeper is not a crew settler its chunk would drop unsaved");
                    Works.handOverLoad(level, k);
                    h.assertTrue(k.getMainHandItem().isEmpty(), "the keeper still holds " + k.getMainHandItem());
                    int owed = Works.OwedLoads.owedTo(level, heart.getBlockPos());
                    h.assertTrue(owed == 64 && diamonds(h, barrel) == 0, owed + " diamonds are owed to the Heart and " + diamonds(h, barrel)
                            + " in the Hoard, not 64 owed and none put away yet");
                })
                .thenWaitUntil(() -> h.assertTrue(diamonds(h, barrel) == 64, "the owed diamonds aren't in the Hoard: " + diamonds(h, barrel)))
                .thenExecute(() -> h.assertTrue(Works.OwedLoads.owedTo(level, heart.getBlockPos()) == 0 && onPile(heart) == 0 && lying(h) == 0,
                        "after they were put away, " + Works.OwedLoads.owedTo(level, heart.getBlockPos()) + " are still owed, " + onPile(heart)
                                + " on the pile and " + lying(h) + " on the ground"))
                .thenSucceed();
    }

    /** The Heart of the sharing test: clear of the site to the north-west and the rock to the east. */
    private static final BlockPos SHARE_HEART = new BlockPos(4, 1, 19);
    /** The hall's door, in the west face of the rock (x 15-23, y 1-7, z 13-23): the hall runs east, x 15-23, y 0-5, z 14-22. */
    private static final BlockPos HALL_DOOR = new BlockPos(15, 1, 18);
    /**
     * The site's door's ground: the test platform (SitingGameTests) runs east from it, x 3-11, z 3-11, its pad (the berm and the landing) x 1-12,
     * z 2-12; raised with no reshaping, so the land round the pad (the rock) is left as it is.
     */
    private static final BlockPos SITE_GROUND = new BlockPos(3, 0, 7);

    /**
     * A dig wanting 13 diggers holds the whole reserve of 2, and then a site is begun: the site gets a builder while the hall is still being dug, and the
     * dig keeps one. Before, the rooms were staffed in the order they were begun, the dig's want (no cap since 0.5.6) took every settler, and a site
     * lays its courses only with builders: it waited for the dig to be done.
     */
    @GameTest(template = "arena", timeoutTicks = 1600, batch = "testrace")
    public static void aSiteBegunAfterADigGetsBuildersFromAShortReserve(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.DIRT.defaultBlockState());
                for (int y = -1; y >= -3; y--) {                 // the arena floats: a raise wants solid ground under its site
                    h.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
                }
            }
        }
        for (int x = 15; x < 24; x++) {
            for (int z = 13; z < 24; z++) {
                for (int y = 1; y <= 7; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
                }
            }
        }
        HeartBlockEntity heart = heart(h, SHARE_HEART);
        heart.addToReserve(2, 0);
        BlockPos door = h.absolutePos(HALL_DOOR);
        String dug = heart.works().dig(level, PlanPlacement.compute(hall(), door, Direction.EAST), true);
        h.assertTrue(dug == null, "the hall was refused: " + dug);
        PlanPlacement site = PlanPlacement.onGround(SitingGameTests.platform(), h.absolutePos(SITE_GROUND), Direction.EAST);
        boolean[] begun = {false};
        h.succeedWhen(() -> {
            Works works = heart.works();
            if (!begun[0]) {
                h.assertTrue(works.crewOf(level, door).size() == 2 && heart.reserveWorkers() == 0, "the dig hasn't taken the whole reserve yet: "
                        + works.crewOf(level, door).size() + " diggers, " + heart.reserveWorkers() + " left");
                String no = works.raise(level, site, true, false);
                h.assertTrue(no == null, "the site was refused: " + no);
                begun[0] = true;
            }
            h.assertTrue(!works.digs().isEmpty(), "the hall was dug out before the site had a builder: the dig kept the whole reserve");
            h.assertTrue(!works.sites().isEmpty(), "the site is gone");
            SiteWork s = works.sites().get(0);
            int diggers = works.crewOf(level, door).size();
            h.assertTrue(works.crewOf(level, s.key()).size() >= 1, "the site begun after the dig has no builder: the dig (wanting "
                    + works.digs().get(0).crewWanted() + ") holds " + diggers + ", the reserve has " + heart.reserveWorkers());
            h.assertTrue(diggers >= 1, "the dig gave up every digger");
        });
    }
}
