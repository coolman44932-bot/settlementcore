package claude.settlementcore.test;

import claude.settlementcore.Nbt;
import claude.settlementcore.Core;
import claude.settlementcore.settlement.Reserve;
import claude.settlementcore.settlement.Roll;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.animal.pig.Pig;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who is up and who waits (core/settlement/Roll and Reserve, docs/plans/greatplan-first-spawning.md section B item 2): the
 * Warren Heart's roll of rats and its horde's two counts lifted into parts with no block entity. Checked as such: the counts
 * and what refuses them, the ids and what they release, the keys they save under (the Heart's own, and a Pool's), and the one
 * that rises from the reserve and goes back if it cannot be made. Vanilla animals stand in for settlers.
 */
@GameTestHolder(Core.MODID)
public class RollReserveGameTests {

    /** Two counts, and what takes from or adds to them: a take refuses at none of the kind, an add never goes under none. */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aReserveCountsTwoKindsAndNeverGoesUnderNone(GameTestHelper h) {
        Reserve r = new Reserve("horde_slaves", "horde_clanrats");
        r.add(2, 1);
        h.assertTrue(r.workers() == 2 && r.soldiers() == 1 && r.count() == 3, "2 workers and a soldier added counted " + r.workers() + " and " + r.soldiers());
        h.assertTrue(r.take(false) && r.take(false), "a worker could not be taken of 2");
        h.assertTrue(!r.take(false) && r.workers() == 0, "a third worker was taken of 2: " + r.workers() + " left");
        h.assertTrue(r.soldiers() == 1 && r.take(true) && !r.take(true) && r.soldiers() == 0, "the soldier was not taken once and then refused: " + r.soldiers() + " left");
        h.assertTrue(r.count() == 0, "an empty reserve counts " + r.count());
        r.add(-5, -5);
        h.assertTrue(r.workers() == 0 && r.soldiers() == 0, "taking 5 from nothing left " + r.workers() + " and " + r.soldiers());
        r.add(3, 0);
        r.add(-1, 4);
        h.assertTrue(r.workers() == 2 && r.soldiers() == 4, "3 and 0 then -1 and 4 make " + r.workers() + " and " + r.soldiers() + ", not 2 and 4");
        r.add(-5, -1);
        h.assertTrue(r.workers() == 0 && r.soldiers() == 3, "-5 and -1 from 2 and 4 make " + r.workers() + " and " + r.soldiers() + ", not 0 and 3");
        h.succeed();
    }

    /**
     * The counts are saved under the keys the race names and nothing else: the Heart's own pair, and a Pool's. Each reads back
     * from its own tag, and from a tag written the way the Heart writes its horde; a tag without the key changes nothing and
     * says so (a settlement from before the reserve was counted), and one with only the workers' key has no soldiers.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aReserveSavesUnderTheRacesKeysAndReadsThemBack(GameTestHelper h) {
        Reserve heart = new Reserve("horde_slaves", "horde_clanrats");
        heart.add(7, 3);
        CompoundTag tag = new CompoundTag();
        heart.save(tag);
        h.assertTrue(tag.keySet().equals(Set.of("horde_slaves", "horde_clanrats")) && tag.getIntOr("horde_slaves", 0) == 7 && tag.getIntOr("horde_clanrats", 0) == 3,
                "the Heart's reserve saved as " + tag);
        Reserve pool = new Reserve("skinks", "saurus");
        pool.add(4, 2);
        CompoundTag poolTag = new CompoundTag();
        pool.save(poolTag);
        h.assertTrue(poolTag.keySet().equals(Set.of("skinks", "saurus")) && poolTag.getIntOr("skinks", 0) == 4 && poolTag.getIntOr("saurus", 0) == 2,
                "a Pool's reserve saved as " + poolTag);

        Reserve back = new Reserve("skinks", "saurus");
        h.assertTrue(back.load(poolTag) && back.workers() == 4 && back.soldiers() == 2, "a Pool's tag read as " + back.workers() + " and " + back.soldiers());
        CompoundTag written = new CompoundTag();                     // as the Heart's own save writes its horde
        written.putInt("horde_slaves", 12);
        written.putInt("horde_clanrats", 5);
        Reserve fromHeart = new Reserve("horde_slaves", "horde_clanrats");
        h.assertTrue(fromHeart.load(written) && fromHeart.workers() == 12 && fromHeart.soldiers() == 5, "the Heart's tag read as " + fromHeart.workers() + " and " + fromHeart.soldiers());
        back.add(-3, -1);
        h.assertTrue(!back.load(tag) && back.workers() == 1 && back.soldiers() == 1,
                "a tag with other keys read as " + back.workers() + " and " + back.soldiers() + ", or said it was read");
        CompoundTag half = new CompoundTag();
        half.putInt("skinks", 9);
        h.assertTrue(back.load(half) && back.workers() == 9 && back.soldiers() == 0, "a tag with only the workers read as " + back.workers() + " and " + back.soldiers());
        h.succeed();
    }

    /**
     * One rises out of the reserve at the cell asked, of the kind asked, made by the race's callback. With none of that kind the
     * callback is never asked and nothing is made; when it cannot make one (null) the one taken goes back, and it is the same
     * kind that goes back.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aReserveRisesOneAndGivesItBackIfNoneCanBeMade(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
        Reserve r = new Reserve("skinks", "saurus");
        r.add(1, 1);
        List<String> asked = new ArrayList<>();
        Reserve.Raiser<Pig> make = (lv, cell, soldier) -> {
            asked.add(cell.toShortString() + " " + soldier);
            return EntityType.PIG.create(lv, net.minecraft.world.entity.EntitySpawnReason.MOB_SUMMONED);
        };
        Pig up = r.rise(level, at, true, make);
        h.assertTrue(up != null && r.soldiers() == 0 && r.workers() == 1 && asked.equals(List.of(at.toShortString() + " true")),
                "a soldier rose as " + up + ", leaving " + r.workers() + " and " + r.soldiers() + " (asked " + asked + ")");
        h.assertTrue(r.rise(level, at, true, make) == null && asked.size() == 1 && r.workers() == 1 && r.soldiers() == 0,
                "a soldier rose of none, or the callback was asked: " + asked);
        int[] tried = {0};
        Reserve.Raiser<Pig> cannot = (lv, cell, soldier) -> {
            tried[0]++;
            return null;
        };
        h.assertTrue(r.rise(level, at, false, cannot) == null && tried[0] == 1 && r.workers() == 1 && r.soldiers() == 0,
                "a worker that could not be made left " + r.workers() + " and " + r.soldiers() + " after " + tried[0] + " tries, not 1 and 0");
        r.add(0, 2);
        h.assertTrue(r.rise(level, at, true, cannot) == null && r.soldiers() == 2 && r.workers() == 1,
                "a soldier that could not be made left " + r.workers() + " and " + r.soldiers() + ", not 1 and 2");
        h.succeed();
    }

    /**
     * The roll holds each id once, and counts them; taking one off with a release tells it once, and only if it was on the roll;
     * the ids are read only.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aRollKeepsWhoIsUpAndReleasesWhoGoes(GameTestHelper h) {
        Roll roll = new Roll("rats");
        UUID a = new UUID(1, 1), b = new UUID(1, 2);
        h.assertTrue(roll.add(a) && !roll.add(a) && roll.add(b), "an id was added twice or not at all");
        h.assertTrue(roll.count() == 2 && roll.has(a) && roll.has(b) && !roll.has(new UUID(1, 3)), "the roll counts " + roll.count());
        List<UUID> released = new ArrayList<>();
        h.assertTrue(roll.remove(a, released::add) && released.equals(List.of(a)) && !roll.has(a) && roll.count() == 1,
                "taking " + a + " off released " + released + " and left " + roll.count());
        h.assertTrue(!roll.remove(a, released::add) && released.equals(List.of(a)), "an id that was not on the roll was released: " + released);
        h.assertTrue(roll.remove(b) && !roll.remove(b) && roll.count() == 0, "an id without a release was not taken off once");
        roll.add(a);
        boolean readOnly = false;
        try {
            roll.ids().add(b);
        } catch (UnsupportedOperationException e) {
            readOnly = true;
        }
        h.assertTrue(readOnly && roll.count() == 1, "the roll's ids were changed through the view");
        h.succeed();
    }

    /**
     * The ids are saved as the Heart saves its rats, a list of int arrays, under the key the race names: a list written by hand the
     * Heart's way is equal, it loads into a roll that held others and leaves exactly those, in order, and a tag without the key
     * empties it.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aRollSavesItsIdsUnderItsKey(GameTestHelper h) {
        UUID x = new UUID(7, 1), y = new UUID(7, 2), z = new UUID(7, 3);
        Roll roll = new Roll("rats");
        roll.add(x);
        roll.add(y);
        roll.add(z);
        CompoundTag tag = new CompoundTag();
        roll.save(tag);
        ListTag byHand = new ListTag();
        for (UUID id : List.of(x, y, z)) {
            byHand.add(Nbt.uuid(id));
        }
        h.assertTrue(tag.keySet().equals(Set.of("rats")) && byHand.equals(tag.get("rats")), "the roll saved as " + tag + ", not as the Heart saves rats: " + byHand);
        Roll other = new Roll("rats");
        other.add(new UUID(9, 9));
        other.load(tag);
        h.assertTrue(new ArrayList<>(other.ids()).equals(List.of(x, y, z)), "loaded, the roll holds " + other.ids());
        Roll skinks = new Roll("skinks");
        skinks.add(x);
        CompoundTag skinkTag = new CompoundTag();
        skinks.save(skinkTag);
        h.assertTrue(skinkTag.keySet().equals(Set.of("skinks")), "a Pool's roll saved under " + skinkTag.keySet());
        other.load(skinkTag);
        h.assertTrue(other.count() == 0, "a tag without the key left " + other.count() + " on the roll");
        h.succeed();
    }

    /**
     * The roll names its mobs that are in a loaded chunk and alive, of the class asked, in the order they were added: a pig and a
     * cow, a pig at no health, and an id of nothing in the world are on the roll; the pigs are the one alive pig, the animals are
     * the pig and the cow.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void aRollNamesTheLoadedLivingMobsOfAKind(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Pig alive = h.spawn(EntityType.PIG, new BlockPos(2, 2, 2));
        Pig dying = h.spawn(EntityType.PIG, new BlockPos(4, 2, 2));
        Cow cow = h.spawn(EntityType.COW, new BlockPos(2, 2, 4));
        dying.setHealth(0f);
        Roll roll = new Roll("rats");
        roll.add(alive.getUUID());
        roll.add(dying.getUUID());
        roll.add(new UUID(5, 5));
        roll.add(cow.getUUID());
        List<Pig> pigs = roll.loaded(level, Pig.class);
        h.assertTrue(pigs.size() == 1 && pigs.get(0) == alive, "the roll's loaded living pigs are " + pigs + ", not the one alive");
        List<Animal> animals = roll.loaded(level, Animal.class);
        h.assertTrue(animals.size() == 2 && animals.get(0) == alive && animals.get(1) == cow, "the roll's loaded living animals are " + animals);
        List<Entity> all = roll.loaded(level, Entity.class);
        h.assertTrue(all.size() == 2, "the roll's loaded living entities are " + all);
        h.succeed();
    }
}
