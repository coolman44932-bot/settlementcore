package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.room.DigJob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * DigJob's seams for other kinds of job (the surface builder's RaiseJob, docs/plans/surface-builder.md section 3), checked
 * with no world: a kind of job's own save keys come back through {@code readSaved} and {@code loadMore}, and a plain dig's
 * save is what it was. And the claim's own rules the seams touched: a dig that pokes over the build height still finishes.
 */
@GameTestHolder(Core.MODID)
@PrefixGameTestTemplate(false)
public class DigJobSeamGameTests {

    /** A kind of job with a key of its own, as RaiseJob's earthworks will be. */
    static final class Keyed extends DigJob {
        int extra;

        Keyed(String room, BlockPos door, Direction facing) {
            super(room, door, facing);
        }

        @Override
        protected void saveMore(CompoundTag t) {
            t.putInt("extra", extra);
        }

        @Override
        protected void loadMore(CompoundTag t) {
            extra = t.getInt("extra");
        }

        static Keyed reload(CompoundTag t) {
            return new Keyed(t.getString("room"), savedDoor(t), savedFacing(t)).readSaved(t);
        }
    }

    /**
     * A kind of job saves its own key beside a dig's and gets it back, with the dig's keys; and a plain dig loads as it
     * did. Fails if {@code loadMore} is never called (step 1 of the surface builder shipped it so: review of 98f7812).
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "digseam")
    public static void aKindOfJobGetsItsOwnKeysBack(GameTestHelper h) {
        Keyed k = new Keyed("seam_test", new BlockPos(3, -7, 11), Direction.NORTH);
        k.extra = 7;
        k.naturalOnly(null);
        CompoundTag saved = k.save();
        Keyed back = Keyed.reload(saved);
        h.assertTrue(back.extra == 7, "its own key came back as " + back.extra + ", not 7: loadMore wasn't called");
        h.assertTrue(back.save().equals(saved), "saved again it differs: " + back.save() + " vs " + saved);
        h.assertTrue(back.door.equals(new BlockPos(3, -7, 11)) && back.facing == Direction.NORTH,
                "the door or facing changed: " + back.door + " " + back.facing);

        DigJob plain = new DigJob("seam_test", new BlockPos(-2, 40, 5), Direction.WEST);
        CompoundTag p = plain.save();
        h.assertTrue(!p.contains("extra") && DigJob.load(p).save().equals(p), "a plain dig's save changed on a round trip: " + p);
        h.succeed();
    }

    /**
     * A dig whose top pokes over the build height finishes (the review of 51b59f8): the cells over the limit are done with
     * nothing put, as a claim always did with them. {@code Level#isLoaded} is false over the build height too, so when the
     * claim first skipped cells whose chunk wasn't loaded it skipped those for good, the dig never finished, and the Heart's
     * queue stood still behind it (a Skaven plan item doesn't stop a room being planned up there: a Furnace Chambers with its
     * door at y 311 or more). The engine's test platform dug in the air over the plot's middle, its top 2 courses over the
     * limit, claimed and gnawed by a made-up rat until nothing is handed out.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "digseam")
    public static void aDigOverTheBuildHeightFinishes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int top = level.getMaxBuildHeight() - 1;
        BlockPos c = h.absolutePos(new BlockPos(20, 0, 20));
        DigJob job = new DigJob(SitingGameTests.platform().id(), new BlockPos(c.getX(), top - 1, c.getZ()), Direction.EAST).unshored();
        BoundingBox box = job.placement().box();
        h.assertTrue(box.minY() < top && box.maxY() > top, "the dig's box " + box + " doesn't poke over the build height " + top);
        clear(level, box);
        UUID rat = new UUID(0xd16L, 1L);
        int claims = 0;
        while (claims < 400 && !job.isDone() && job.claim(level, rat, 6, level.getGameTime()) != null) {
            job.complete(level, rat);
            claims++;
        }
        boolean done = job.isDone();
        clear(level, box);
        h.assertTrue(done, "the dig stopped at " + Math.round(job.progress() * 100) + "% (" + job.finishedCount() + " cells done) after " + claims
                + " claims, with nothing more handed out: its cells over the build height " + top + " were never finished");
        h.succeed();
    }

    /** The box's cells under the build height, air. */
    private static void clear(ServerLevel level, BoundingBox box) {
        for (BlockPos q : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), Math.min(box.maxY(), level.getMaxBuildHeight() - 1),
                box.maxZ())) {
            if (!level.getBlockState(q).isAir()) {
                level.setBlock(q, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
    }
}
