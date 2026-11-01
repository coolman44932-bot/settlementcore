package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.room.RoomTemplates;
import claude.settlementcore.work.Den;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.ClockAdjustment;

import java.util.List;

/**
 * What the move to 26.1 changed under the engine (the review of cccf720): a room template saved on 1.21.1 still reads whole, and a night's
 * sleep is read off the world clock without moving it.
 */
@GameTestHolder(Core.MODID)
public class UpgradeGameTests {

    /** A 1.21.1 room template's chain (renamed iron_chain in 26.1) reads back as an iron chain, not as the air a raw read gives. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aRoomTemplateSavedOn1211ReadsItsRenamedBlocks(GameTestHelper h) {
        CompoundTag file = new CompoundTag();
        file.putInt("DataVersion", 3955);                        // 1.21.1
        ListTag size = new ListTag();
        for (int i = 0; i < 3; i++) {
            size.add(IntTag.valueOf(1));
        }
        file.put("size", size);
        ListTag palette = new ListTag();
        CompoundTag chain = new CompoundTag();
        chain.putString("Name", "minecraft:chain");
        CompoundTag props = new CompoundTag();
        props.putString("axis", "y");
        props.putString("waterlogged", "false");
        chain.put("Properties", props);
        palette.add(chain);
        file.put("palette", palette);
        ListTag blocks = new ListTag();
        CompoundTag cell = new CompoundTag();
        ListTag pos = new ListTag();
        for (int i = 0; i < 3; i++) {
            pos.add(IntTag.valueOf(0));
        }
        cell.put("pos", pos);
        cell.putInt("state", 0);
        blocks.add(cell);
        file.put("blocks", blocks);
        file.put("entities", new ListTag());
        h.assertTrue(NbtUtils.readBlockState(BuiltInRegistries.BLOCK, chain.copy()).isAir(),
                "the raw read of minecraft:chain is not air: the case this test stands for is gone");
        List<RoomTemplates.Entry> cells = RoomTemplates.entries(file, BuiltInRegistries.BLOCK);
        h.assertTrue(cells.size() == 1 && cells.get(0).state().is(Blocks.IRON_CHAIN), "the 1.21.1 chain read back as " + cells);
        h.succeed();
    }

    /** The night's length is the adjustment's, read without moving the clock, and a marker's agrees with where the clock really goes. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aNightsSleepIsReadOffTheClockWithoutMovingIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var clock = level.dimensionType().defaultClock().orElseThrow();
        var clocks = level.getServer().clockManager();
        long before = clocks.getTotalTicks(clock);
        long relative = Den.skipped(level, new ClockAdjustment.Relative(1200));
        long absolute = Den.skipped(level, new ClockAdjustment.Absolute(before + 500));
        ClockAdjustment wake = new ClockAdjustment.Marker(ClockTimeMarkers.WAKE_UP_FROM_SLEEP);
        long read = Den.skipped(level, wake);
        h.assertTrue(clocks.getTotalTicks(clock) == before, "reading the adjustments moved the clock: " + before + " -> " + clocks.getTotalTicks(clock));
        h.assertTrue(relative == 1200 && absolute == 500, "a relative 1200 read " + relative + ", an absolute 500 ahead read " + absolute);
        wake.apply(clocks, clock);
        long moved = clocks.getTotalTicks(clock) - before;
        clocks.setTotalTicks(clock, before);
        h.assertTrue(read == moved && read >= 0, "the wake-up marker read " + read + " ticks, but moves the clock " + moved);
        h.succeed();
    }
}
