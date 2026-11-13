package claude.settlementcore.test;

import claude.settlementcore.Core;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The survival harness itself ({@link SurvivalGod}, {@link BatchWorld}, {@link PlayerLoadedChunks}), proved on vanilla blocks and
 * items so every race's survival tests stand on something checked. All in the engine's digsite (solid stone, 44 x 20 x 44: helper
 * y 1 to 20), with rooms carved out of it.
 *
 * <ul>
 * <li>survivalgod: a day that stands still, Normal (Peaceful would feed him), keepInventory off: placing, mining, crafting, picking
 * up, eating, dying and respawning.
 * <li>survivalhard: Hard, for starving.
 * <li>survivalnight: midnight, natural spawning on, Hard: a dark box gets a monster, a lit one none.
 * <li>survivalchunks: chunks let go of tick only round the god.
 * <li>survivalworld: a world set and put back by hand (it changes the difficulty in mid-batch, so alone).
 * </ul>
 */
@GameTestHolder(Core.MODID)
@BatchWorld(batch = "survivalgod", time = 6000, daylightCycle = BatchWorld.Rule.OFF, keepInventory = BatchWorld.Rule.OFF,
        weather = BatchWorld.Weather.CLEAR, difficulty = Difficulty.NORMAL)
@BatchWorld(batch = "survivalhard", time = 6000, daylightCycle = BatchWorld.Rule.OFF, difficulty = Difficulty.HARD)
@BatchWorld(batch = "survivalnight", time = 18000, daylightCycle = BatchWorld.Rule.OFF, mobSpawning = BatchWorld.Rule.ON,
        difficulty = Difficulty.HARD, weather = BatchWorld.Weather.CLEAR)
@BatchWorld(batch = "survivalchunks", time = 6000, daylightCycle = BatchWorld.Rule.OFF, simulationDistance = 2)
public class SurvivalHarnessGameTests {

    /** Carves air out of the digsite, corners inclusive (test-relative). */
    static void carve(GameTestHelper h, BlockPos from, BlockPos to) {
        for (BlockPos p : BlockPos.betweenClosed(from, to)) {
            h.setBlock(p, Blocks.AIR);
        }
    }

    /** A room 2 to 8 east, 2 to 4 up, 2 to 6 south: stone floor at y 1, the god at (3, 2, 4), the wall stone at (9, 2, 4) a reach off. */
    static void room(GameTestHelper h) {
        carve(h, new BlockPos(2, 2, 2), new BlockPos(8, 4, 6));
    }

    static final BlockPos STAND = new BlockPos(3, 2, 4);

    /** How many of {@code item} lie on the ground within the test's bounds. */
    static int lying(GameTestHelper h, Item item) {
        return h.getLevel().getEntitiesOfClass(ItemEntity.class, h.getBounds(), e -> e.isAlive() && e.getItem().is(item)).stream()
                .mapToInt(e -> e.getItem().getCount()).sum();
    }

    // ---- survivalgod: the god's hands

    /** A block placed from the hotbar is used up: 4 cobblestone, one right-click on the floor, 3 left and the block standing there. */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "survivalgod")
    public static void placingABlockUsesUpTheHotbarItem(GameTestHelper h) {
        room(h);
        SurvivalGod god = SurvivalGod.join(h, STAND);
        god.give(Items.COBBLESTONE, 4).hold(Items.COBBLESTONE);
        BlockPos placed = god.placeFromHotbar(new BlockPos(5, 1, 4), Direction.UP);
        h.assertTrue(placed.equals(new BlockPos(5, 2, 4)), "the cobblestone went to " + placed + ", not on the floor at 5, 2, 4");
        h.assertBlockPresent(Blocks.COBBLESTONE, placed);
        h.assertTrue(god.count(Items.COBBLESTONE) == 3, "placing one left " + god.count(Items.COBBLESTONE) + " cobblestone of 4 (survival uses it up)");
        h.succeed();
    }

    /**
     * Stone mined by hand takes its time (1.5 hardness, the wrong tool: 150 ticks on the ground) and drops nothing, as in survival:
     * the job counts the client's ticks, and the server checks its own.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "survivalgod")
    public static void stoneMinedByHandTakesLongAndDropsNothing(GameTestHelper h) {
        room(h);
        BlockPos wall = new BlockPos(9, 2, 4);
        SurvivalGod god = SurvivalGod.join(h, new BlockPos(7, 2, 4));
        h.assertTrue(god.held().isEmpty(), "the god should mine with his bare hand, not " + god.held());
        SurvivalGod.Job job = god.mine(wall);
        h.startSequence()
                .thenWaitUntil(job::assertDone)
                .thenExecute(() -> {
                    h.assertBlockPresent(Blocks.AIR, wall);
                    h.assertTrue(job.ticks() >= 100, "stone fell to a bare hand in " + job.ticks() + " ticks; it takes about 150 (" + job.status() + ")");
                })
                .thenIdle(20)
                .thenExecute(() -> h.assertTrue(lying(h, Items.COBBLESTONE) == 0 && god.count(Items.COBBLESTONE) == 0,
                        "stone mined by hand dropped cobblestone (" + lying(h, Items.COBBLESTONE) + " lying, " + god.count(Items.COBBLESTONE) + " carried)"))
                .thenSucceed();
    }

    /** With a wooden pickaxe stone falls in about 23 ticks, drops a cobblestone (picked up off the floor) and wears the pick by one. */
    @GameTest(template = "digsite", timeoutTicks = 300, batch = "survivalgod")
    public static void aWoodenPickaxeDropsCobblestoneAndWears(GameTestHelper h) {
        room(h);
        BlockPos wall = new BlockPos(9, 2, 4);
        SurvivalGod god = SurvivalGod.join(h, new BlockPos(7, 2, 4));
        god.give(Items.WOODEN_PICKAXE, 1).hold(Items.WOODEN_PICKAXE);
        SurvivalGod.Job job = god.mine(wall);
        SurvivalGod.Job[] pick = new SurvivalGod.Job[1];
        h.startSequence()
                .thenWaitUntil(job::assertDone)
                .thenExecute(() -> {
                    h.assertTrue(job.ticks() < 60, "stone took " + job.ticks() + " ticks with a wooden pickaxe (about 23 expected): " + job.status());
                    ItemStack pickaxe = god.held();
                    h.assertTrue(pickaxe.is(Items.WOODEN_PICKAXE) && pickaxe.getDamageValue() == 1, "the pickaxe did not wear by one: " + pickaxe
                            + " damage " + pickaxe.getDamageValue());
                })
                .thenWaitUntil(() -> h.assertTrue(lying(h, Items.COBBLESTONE) + god.count(Items.COBBLESTONE) == 1,
                        "no cobblestone from the mined stone (" + lying(h, Items.COBBLESTONE) + " lying)"))
                .thenExecute(() -> pick[0] = god.pickUp(6))
                .thenWaitUntil(() -> pick[0].assertDone())
                .thenExecute(() -> h.assertTrue(god.count(Items.COBBLESTONE) == 1, "the god carries " + god.count(Items.COBBLESTONE) + " cobblestone, not 1"))
                .thenSucceed();
    }

    /** Sticks at a crafting table: two planks in a column make four sticks, and only those two planks are used. */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "survivalgod")
    public static void craftingSticksAtATableTakesThePlanks(GameTestHelper h) {
        room(h);
        BlockPos table = new BlockPos(5, 2, 4);
        h.setBlock(table, Blocks.CRAFTING_TABLE);
        SurvivalGod god = SurvivalGod.join(h, STAND);
        god.give(Items.OAK_PLANKS, 4);
        ItemStack made = god.craft(table, Items.OAK_PLANKS, null, null, Items.OAK_PLANKS);
        h.assertTrue(made.is(Items.STICK) && made.getCount() == 4, "two planks in a column made " + made + ", not 4 sticks");
        h.assertTrue(god.count(Items.STICK) == 4, "the god carries " + god.count(Items.STICK) + " sticks, not 4 (inventory: " + god.contents() + ")");
        h.assertTrue(god.count(Items.OAK_PLANKS) == 2, "the god has " + god.count(Items.OAK_PLANKS) + " planks left, not 2 (inventory: " + god.contents() + ")");
        h.assertTrue(god.player().containerMenu == god.player().inventoryMenu, "the crafting table was left open: " + god.player().containerMenu);
        h.succeed();
    }

    /** One log in his own 2 x 2 grid makes four planks. */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "survivalgod")
    public static void aLogMakesPlanksInTheInventoryGrid(GameTestHelper h) {
        room(h);
        SurvivalGod god = SurvivalGod.join(h, STAND);
        god.give(Items.OAK_LOG, 1);
        ItemStack made = god.craftInInventory(Items.OAK_LOG);
        h.assertTrue(made.is(Items.OAK_PLANKS) && made.getCount() == 4, "a log made " + made + ", not 4 oak planks");
        h.assertTrue(god.count(Items.OAK_PLANKS) == 4 && god.count(Items.OAK_LOG) == 0, "after the craft: " + god.contents());
        h.succeed();
    }

    /**
     * A craft the player cannot make fails, visibly, and costs nothing: a grid that is no recipe makes nothing and its items come
     * back as the table closes; an ingredient he does not have is named.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "survivalgod")
    public static void anImpossibleCraftFailsAndKeepsTheItems(GameTestHelper h) {
        room(h);
        BlockPos table = new BlockPos(5, 2, 4);
        h.setBlock(table, Blocks.CRAFTING_TABLE);
        SurvivalGod god = SurvivalGod.join(h, STAND);
        god.give(Items.DIRT, 1).give(Items.COBBLESTONE, 1);
        ItemStack made = god.tryCraft(table, Items.DIRT, Items.COBBLESTONE);
        h.assertTrue(made.isEmpty(), "dirt and cobblestone made " + made);
        h.assertTrue(god.lastCraftFailure() != null && god.lastCraftFailure().contains("makes nothing"), "the failure does not say the grid makes nothing: "
                + god.lastCraftFailure());
        h.assertTrue(god.count(Items.DIRT) == 1 && god.count(Items.COBBLESTONE) == 1, "the failed craft cost items: " + god.contents());
        h.assertTrue(lying(h, Items.DIRT) == 0 && lying(h, Items.COBBLESTONE) == 0, "the failed craft's items were dropped, not given back");
        made = god.tryCraft(table, Items.DIAMOND, null, null, Items.STICK);
        h.assertTrue(made.isEmpty() && god.lastCraftFailure() != null && god.lastCraftFailure().contains("no " + Items.DIAMOND),
                "a craft from a diamond he does not have: " + made + ", " + god.lastCraftFailure());
        h.succeed();
    }

    /** Items lying about are picked up by the god's own tick once he stands by them. */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "survivalgod")
    public static void itemsOnTheGroundArePickedUp(GameTestHelper h) {
        room(h);
        SurvivalGod god = SurvivalGod.join(h, STAND);
        for (Vec3 at : List.of(new Vec3(7.5, 2, 2.5), new Vec3(7.5, 2, 6.5), new Vec3(5.5, 2, 6.5))) {
            h.spawnItem(Items.APPLE, at);
        }
        SurvivalGod.Job job = god.pickUp(8);
        h.startSequence()
                .thenWaitUntil(job::assertDone)
                .thenExecute(() -> {
                    h.assertTrue(god.count(Items.APPLE) == 3, "the god picked up " + god.count(Items.APPLE) + " apples of 3 (" + job.status() + ")");
                    h.assertTrue(lying(h, Items.APPLE) == 0, lying(h, Items.APPLE) + " apples still lie about");
                })
                .thenSucceed();
    }

    /** Bread eaten over its use time: food 10 to 15, the loaf gone. */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "survivalgod")
    public static void eatingBreadFillsHunger(GameTestHelper h) {
        room(h);
        SurvivalGod god = SurvivalGod.join(h, STAND);
        god.hunger(10, 0).give(Items.BREAD, 1).hold(Items.BREAD);
        SurvivalGod.Job job = god.eat();
        h.startSequence()
                .thenWaitUntil(job::assertDone)
                .thenExecute(() -> {
                    h.assertTrue(god.food() == 15, "bread took food 10 to " + god.food() + ", not 15");
                    h.assertTrue(god.count(Items.BREAD) == 0, "the loaf is still there");
                    h.assertTrue(job.ticks() >= 30, "bread was eaten in " + job.ticks() + " ticks; eating takes 32");
                })
                .thenSucceed();
    }

    /**
     * Dying drops everything he carries (keepInventory is off in this batch), and Respawn makes a new player at the bed he lay down
     * in (by day it only sets his spawn): full health, empty-handed, a new ServerPlayer.
     */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "survivalgod")
    public static void dyingDropsTheInventoryAndRespawnIsAtTheBed(GameTestHelper h) {
        carve(h, new BlockPos(2, 2, 2), new BlockPos(16, 4, 6));
        BlockPos foot = new BlockPos(13, 2, 4), head = foot.east();
        h.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.FOOT));
        h.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.EAST).setValue(BedBlock.PART, BedPart.HEAD));
        SurvivalGod god = SurvivalGod.join(h, new BlockPos(12, 2, 4));
        god.useOn(foot, Direction.UP);
        ServerPlayer.RespawnConfig spawn = god.player().getRespawnConfig();
        h.assertTrue(spawn != null && spawn.respawnData().pos().equals(h.absolutePos(head)), "lying down in the bed by day did not set the god's spawn there: "
                + (spawn == null ? "none" : h.relativePos(spawn.respawnData().pos())) + " (told: " + god.chat() + ")");
        god.moveTo(new BlockPos(4, 2, 4));
        god.give(Items.COBBLESTONE, 5).give(Items.WOODEN_PICKAXE, 1);
        ServerPlayer before = god.player();
        god.die();
        AABB roomBox = h.absoluteAABB(new AABB(2, 2, 2, 17, 5, 7));
        int cobble = h.getLevel().getEntitiesOfClass(ItemEntity.class, roomBox, e -> e.getItem().is(Items.COBBLESTONE)).stream().mapToInt(e -> e.getItem().getCount()).sum();
        int picks = h.getLevel().getEntitiesOfClass(ItemEntity.class, roomBox, e -> e.getItem().is(Items.WOODEN_PICKAXE)).size();
        h.assertTrue(cobble == 5 && picks == 1, "dying dropped " + cobble + " cobblestone of 5 and " + picks + " pickaxe of 1");
        ServerPlayer after = god.respawn();
        h.assertTrue(after != before && after.isAlive() && god.player() == after, "respawning gave no new living player");
        h.assertTrue(after.getHealth() == after.getMaxHealth(), "the respawned god has " + after.getHealth() + " health");
        h.assertTrue(god.count(Items.COBBLESTONE) == 0 && god.count(Items.WOODEN_PICKAXE) == 0, "the respawned god kept his things with keepInventory off: "
                + god.contents());
        double fromBed = after.position().distanceTo(Vec3.atBottomCenterOf(h.absolutePos(head)));
        h.assertTrue(fromBed <= 3, "the god came back " + fromBed + " blocks from his bed, at " + god.at());
        h.succeed();
    }

    // ---- survivalhard

    /** On Hard hunger kills: an empty stomach takes the last heart (Normal stops at half a heart, Easy at five). */
    @GameTest(template = "digsite", timeoutTicks = 300, batch = "survivalhard")
    public static void aStarvingGodDiesOnHard(GameTestHelper h) {
        h.assertTrue(h.getLevel().getDifficulty() == Difficulty.HARD, "the survivalhard batch is " + h.getLevel().getDifficulty() + ", not Hard");
        room(h);
        SurvivalGod god = SurvivalGod.join(h, STAND);
        god.hunger(0, 0);
        god.player().setHealth(1.0F);
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(god.player().isDeadOrDying(), "the starving god still has " + god.health() + " health, food " + god.food()))
                .thenSucceed();
    }

    // ---- survivalnight

    /**
     * The night batch's world while it runs: Hard, natural spawning on, midnight with the clock stopped, clear. (It is put back as the
     * batch ends, and logged; survivalworld proves the putting back.)
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "survivalnight")
    public static void theNightBatchIsHardDarkAndSpawning(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        GameRules rules = level.getGameRules();
        WorldConditions now = WorldConditions.read(level);
        h.assertTrue(level.getDifficulty() == Difficulty.HARD, "difficulty " + level.getDifficulty() + ", not Hard");
        h.assertTrue(rules.get(GameRules.SPAWN_MOBS) && rules.get(GameRules.SPAWN_MONSTERS) && level.isSpawningMonsters(), "monsters do not spawn: " + now);
        h.assertTrue(!rules.get(GameRules.ADVANCE_TIME), "the clock runs");
        h.assertTrue(now.time() != null && now.time() == 18000L, "the time is " + now.time() + ", not 18000");
        h.assertTrue(level.isDarkOutside() && now.weather() == BatchWorld.Weather.CLEAR, "not a clear night: dark " + level.isDarkOutside() + ", " + now.weather());
        h.succeed();
    }

    /**
     * Natural spawning near a survival god at night, on Hard: an unlit hollow in the stone gets a monster, the same hollow lit by
     * torches none. Both 9 x 3 x 9, sealed in the digsite (no sky), more than 24 blocks from the god on the digsite's top and from the
     * world spawn (the first plot's corner: no monster spawns within 24 of either) and well within 128 of him.
     *
     * <p>The flat test world's open ground fills the mob cap within ticks once it is night, and a full cap spawns nothing anywhere;
     * every monster outside the two hollows, within 160 blocks, is taken away each tick so the cap stays free for them. That is the
     * test's only hand in it: where and whether a monster comes is NaturalSpawner's.
     */
    @GameTest(template = "digsite", timeoutTicks = 3000, batch = "survivalnight")
    public static void aDarkHollowSpawnsAMonsterAndALitOneDoesNot(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        carve(h, new BlockPos(26, 2, 2), new BlockPos(34, 4, 10));            // dark
        carve(h, new BlockPos(2, 2, 26), new BlockPos(10, 4, 34));            // lit
        for (BlockPos t : List.of(new BlockPos(6, 2, 30), new BlockPos(3, 2, 27), new BlockPos(9, 2, 27), new BlockPos(3, 2, 33), new BlockPos(9, 2, 33))) {
            h.setBlock(t, Blocks.TORCH);
        }
        AABB dark = h.absoluteAABB(new AABB(26, 2, 2, 35, 5, 11));
        AABB lit = h.absoluteAABB(new AABB(2, 2, 26, 11, 5, 35));
        SurvivalGod god = SurvivalGod.join(h, new BlockPos(40, 21, 40));
        Vec3 spawn = Vec3.atCenterOf(level.getRespawnData().pos());
        h.assertTrue(dark.distanceToSqr(spawn) > 24.5 * 24.5 && lit.distanceToSqr(spawn) > 24.5 * 24.5, "a hollow is within 24.5 blocks of the world spawn "
                + h.relativeVec(spawn) + ": nothing spawns within 24 of it");
        h.assertTrue(dark.distanceToSqr(god.player().position()) > 25 * 25 && lit.distanceToSqr(god.player().position()) > 25 * 25,
                "a hollow is within 25 blocks of the god");
        h.onEachTick(() -> {
            if (god.hasLeft()) {
                return;
            }
            AABB near = god.player().getBoundingBox().inflate(160);
            for (Mob m : level.getEntitiesOfClass(Mob.class, near, m -> m instanceof Enemy && m.isAlive())) {
                if (m.getBoundingBox().intersects(lit)) {
                    h.fail("a " + m.getType().toShortString() + " spawned in the lit hollow at " + h.relativeVec(m.position()) + " (block light "
                            + level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, m.blockPosition()) + ")");
                } else if (!m.getBoundingBox().intersects(dark)) {
                    m.discard();
                }
            }
        });
        h.succeedWhen(() -> {
            List<Mob> in = level.getEntitiesOfClass(Mob.class, dark, m -> m instanceof Enemy && m.isAlive());
            h.assertTrue(!in.isEmpty(), "no monster in the dark hollow yet (light there " + level.getMaxLocalRawBrightness(BlockPos.containing(dark.getCenter()))
                    + ", difficulty " + level.getDifficulty() + ")");
        });
    }

    // ---- survivalchunks

    /**
     * Released chunks tick only near the god: with the plot's chunks let go of and the god 300 blocks off (simulation distance 2 in
     * this batch), a furnace and a pig on the plot stand still, while the same furnace and pig beside him go on: the furnace smelts its
     * raw iron, the pig ages.
     */
    @GameTest(template = "digsite", timeoutTicks = 600, batch = "survivalchunks")
    public static void aReleasedChunkFarFromTheGodStopsTicking(GameTestHelper h) {
        BlockPos farFurnace = new BlockPos(20, 21, 20), nearFurnace = new BlockPos(302, 25, 0), there = new BlockPos(300, 25, 0);
        for (BlockPos p : BlockPos.betweenClosed(new BlockPos(297, 24, -3), new BlockPos(303, 24, 3))) {
            h.setBlock(p, Blocks.STONE);                                      // somewhere for him to stand, far off
        }
        for (BlockPos f : List.of(farFurnace, nearFurnace)) {
            h.setBlock(f, Blocks.FURNACE);
            FurnaceBlockEntity furnace = h.getBlockEntity(f, FurnaceBlockEntity.class);
            furnace.setItem(0, new ItemStack(Items.RAW_IRON));
            furnace.setItem(1, new ItemStack(Items.COAL));
        }
        Pig farPig = h.spawn(EntityType.PIG, new BlockPos(22, 21, 20));
        Pig nearPig = h.spawn(EntityType.PIG, new BlockPos(300, 25, 2));
        for (Pig p : List.of(farPig, nearPig)) {
            p.setNoAi(true);
            p.setPersistenceRequired();
        }
        SurvivalGod god = SurvivalGod.join(h, there);
        PlayerLoadedChunks.Released released = PlayerLoadedChunks.release(h, god, -1);
        h.assertTrue(released.isReleased(h.absolutePos(farFurnace)), "the far furnace's chunk was not released: released " + released.released()
                + ", shared " + released.shared());
        // the god's chunks become entity-ticking some ticks after he joins (his ticket takes effect late, and later still in a full
        // suite: the laptop's first run counted 0 and 95 of 200 ticks), so the pigs are counted from when his chunk does
        int[] start = new int[2];
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(h.getLevel().isPositionEntityTicking(nearPig.blockPosition()),
                        "the god's chunk is not entity-ticking yet"))
                .thenExecute(() -> {
                    start[0] = farPig.tickCount;
                    start[1] = nearPig.tickCount;
                })
                .thenWaitUntil(() -> h.assertTrue(nearPig.tickCount - start[1] >= 100, "the pig by the god ticked only "
                        + (nearPig.tickCount - start[1]) + " times since his chunk began entity-ticking"))
                .thenWaitUntil(() -> {
                    FurnaceBlockEntity near = h.getBlockEntity(nearFurnace, FurnaceBlockEntity.class);
                    h.assertTrue(near.getItem(2).is(Items.IRON_INGOT), "the furnace by the god has not smelted yet: " + near.getItem(2)
                            + " (block-ticking " + PlayerLoadedChunks.blockTicking(h, nearFurnace) + ")");
                })
                .thenExecute(() -> {
                    h.assertTrue(!PlayerLoadedChunks.blockTicking(h, farFurnace), "the released chunk under the far furnace still block-ticks");
                    FurnaceBlockEntity far = h.getBlockEntity(farFurnace, FurnaceBlockEntity.class);
                    h.assertTrue(far.getItem(2).isEmpty() && far.getItem(0).is(Items.RAW_IRON), "the furnace on the released plot smelted: in "
                            + far.getItem(0) + ", out " + far.getItem(2));
                    int farTicks = farPig.tickCount - start[0], nearTicks = nearPig.tickCount - start[1];
                    h.assertTrue(farTicks <= 5, "the pig on the released plot ticked " + farTicks + " times after the release");
                    h.assertTrue(nearTicks >= 100, "the pig by the god ticked only " + nearTicks + " times");
                })
                .thenSucceed();
    }

    // ---- survivalworld

    /**
     * A batch's world is set and put back exactly: every condition flipped from what the world has (difficulty, keepInventory, random
     * tick speed, time, the daylight cycle, natural spawning, weather, simulation distance), checked, put back, checked again.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "survivalworld")
    public static void aBatchWorldIsPutBackExactly(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        WorldConditions before = WorldConditions.read(level);
        Difficulty other = before.difficulty() == Difficulty.HARD ? Difficulty.EASY : Difficulty.HARD;
        BatchWorld.Weather weather = before.weather() == BatchWorld.Weather.RAIN ? BatchWorld.Weather.CLEAR : BatchWorld.Weather.RAIN;
        WorldConditions want = WorldConditions.NONE.withDifficulty(other).withKeepInventory(!Boolean.TRUE.equals(before.keepInventory()))
                .withRandomTickSpeed(before.randomTickSpeed() + 4).withTime(1234).withDaylightCycle(!Boolean.TRUE.equals(before.daylightCycle()))
                .withMobSpawning(!Boolean.TRUE.equals(before.mobSpawning())).withWeather(weather).withSimulationDistance(before.simulationDistance() + 2);
        WorldConditions.Saved saved = want.apply(level);
        WorldConditions during = WorldConditions.read(level);
        List<String> wrong = saved.restore(level);
        WorldConditions after = WorldConditions.read(level);
        h.assertTrue(during.difficulty() == other && during.keepInventory() == want.keepInventory() && during.randomTickSpeed().equals(want.randomTickSpeed())
                && during.time() == 1234L && during.daylightCycle() == want.daylightCycle() && during.mobSpawning() == want.mobSpawning()
                && during.weather() == weather && during.simulationDistance().equals(want.simulationDistance()),
                "the world was not set: wanted " + want + ", had " + during);
        h.assertTrue(wrong.isEmpty(), "the world did not come back: " + wrong);
        h.assertTrue(after.equals(before), "the world came back as " + after + ", not " + before);
        h.succeed();
    }
}
