package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.room.DigJob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * DigJob's seams for other kinds of job (the surface builder's RaiseJob, docs/plans/surface-builder.md section 3), checked
 * with no world: a kind of job's own save keys come back through {@code readSaved} and {@code loadMore}, and a plain dig's
 * save is what it was.
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
}
