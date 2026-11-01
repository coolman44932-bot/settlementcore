package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.food.Fare;
import claude.settlementcore.food.Larder;
import claude.settlementcore.ledger.HaulLog;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.settlement.Famine;
import claude.settlementcore.settlement.Ration;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.work.Works;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

/**
 * The settlement's ration (core/settlement/Ration and Famine, docs/plans/greatplan-first-spawning.md section B item 1): it is
 * the Warren Heart's food code lifted into a part with no block entity, so it is checked as that: what is spent first, what is
 * saved under which keys, the larder behind it, the fare, and the eating and starving of each second. No settlement is placed;
 * a stub says what the second asks of one.
 */
@GameTestHolder(Core.MODID)
public class RationGameTests {

    /** A larder of the test's own: the meals still in it, drawn in order, and what is held back for the cooks. */
    private static final class Pantry implements Ration.Pantry {
        final Deque<Larder.Meal> meals = new ArrayDeque<>();
        final int[] held = new int[Fare.GRADES.length];
        int drawn;

        Pantry meals(int n, int points, int grade) {
            for (int i = 0; i < n; i++) {
                meals.add(new Larder.Meal(Items.BREAD, points, grade));
            }
            return this;
        }

        @Override
        public int[] edible() {
            int[] out = new int[Fare.GRADES.length];
            for (Larder.Meal m : meals) {
                out[m.grade()] += m.points();
            }
            return out;
        }

        @Override
        public int[] held() {
            return held.clone();
        }

        @Override
        public Larder.Meal draw() {
            Larder.Meal m = meals.poll();
            drawn += m == null ? 0 : 1;
            return m;
        }
    }

    /** A ration over {@code pantry} holding {@code grades} (loaded as the Heart's own tag would hold them), its roads and its saves recorded. */
    private static final class Table {
        final List<String> roads = new ArrayList<>();
        int saves;
        final Pantry pantry;
        final Ration ration;

        Table(Pantry pantry, int... grades) {
            this.pantry = pantry;
            ration = new Ration(pantry, (from, to, n) -> roads.add(from + ">" + to + ":" + n), () -> saves++);
            ration.load(tagOf(grades, 0f, 0));
        }
    }

    /** A tag as the Warren Heart's own save writes its food: the total, the ration by grade, the hunger and the seconds starving. */
    private static CompoundTag tagOf(int[] larder, float hunger, int starving) {
        CompoundTag t = new CompoundTag();
        t.putInt("food", Arrays.stream(larder).sum());
        t.putIntArray("larder", larder.clone());
        t.putFloat("hunger", hunger);
        t.putInt("starving", starving);
        return t;
    }

    /**
     * The ration is spent worst grade first (slop, then raw, cooked, feast, gorge): 9 out of 4 slop, 3 raw, 5 cooked, 2 feast and 6
     * gorge leaves the cooked at 3 and the feast and gorge whole; a spend of more than there is returns what there was and
     * leaves nothing; a spend of nothing saves nothing; and a spend on something is logged on the road to it.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aRationSpendsTheWorstGradeFirst(GameTestHelper h) {
        Table t = new Table(new Pantry(), 4, 3, 5, 2, 6);
        int spent = t.ration.spendFood(9, "Arming");
        h.assertTrue(spent == 9 && Arrays.equals(t.ration.grades(), new int[]{0, 0, 3, 2, 6}),
                "9 spent of 4 slop, 3 raw, 5 cooked, 2 feast and 6 gorge returned " + spent + " and left " + Arrays.toString(t.ration.grades())
                        + ", not the cooked at 3 and the feast and gorge whole");
        h.assertTrue(t.roads.equals(List.of(HaulLog.LARDER + ">Arming:9")) && t.saves == 1, "the road and the save were " + t.roads + " and " + t.saves);
        spent = t.ration.spendFood(100);
        h.assertTrue(spent == 11 && t.ration.inRation() == 0, "100 spent returned " + spent + " and left " + t.ration.inRation() + ", not 11 and nothing");
        int saves = t.saves;
        h.assertTrue(t.ration.spendFood(5) == 0 && t.saves == saves, "a spend with nothing to spend returned something or saved");
        h.succeed();
    }

    /**
     * A new ration holds 60 raw (a new Heart's). It saves under the Heart's keys, tag for tag: a tag written as the Heart writes
     * one reads back the same and writes back equal; four piles from before the gorge grade load with no gorge, and a larder
     * from before the fare, only a total, loads as all raw.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aRationSavesUnderTheHeartsKeysAndReadsTheHeartsTags(GameTestHelper h) {
        Ration fresh = new Ration(Ration.Pantry.NONE, (a, b, n) -> {
        }, () -> {
        });
        h.assertTrue(Arrays.equals(fresh.grades(), new int[]{0, Ration.STARTER_RAW, 0, 0, 0}) && Ration.STARTER_RAW == 60,
                "a new ration holds " + Arrays.toString(fresh.grades()) + ", not 60 raw");
        CompoundTag heart = tagOf(new int[]{1, 2, 3, 4, 5}, 0.25f, 7);
        Ration r = fresh;
        r.load(heart);
        h.assertTrue(Arrays.equals(r.grades(), new int[]{1, 2, 3, 4, 5}) && r.hunger() == 0.25f && r.starvingSeconds() == 7,
                "the Heart's tag read as " + Arrays.toString(r.grades()) + ", hunger " + r.hunger() + ", starving " + r.starvingSeconds());
        CompoundTag out = new CompoundTag();
        r.save(out);
        h.assertTrue(out.equals(heart), "saved it is " + out + ", not the Heart's " + heart);
        r.load(tagOf(new int[]{9, 8, 7, 6}, 0f, 0));
        h.assertTrue(Arrays.equals(r.grades(), new int[]{9, 8, 7, 6, 0}), "four piles loaded as " + Arrays.toString(r.grades()) + ", not with no gorge");
        CompoundTag old = new CompoundTag();
        old.putInt("food", 17);
        r.load(old);
        h.assertTrue(Arrays.equals(r.grades(), new int[]{0, 17, 0, 0, 0}) && r.hunger() == 0f && r.starvingSeconds() == 0,
                "a total of 17 loaded as " + Arrays.toString(r.grades()) + ", not all raw");
        h.succeed();
    }

    /**
     * When the ration runs dry the larder's containers feed it, item by item: 12 spent of 3 raw behind 3 meals of 5 cooked takes
     * the 3, then two meals (one cooked point left over in the ration, the third meal untouched) and logs 12; with the pantry
     * empty the rest is what the last meal gave. A spend of a lot draws at most 256 meals.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aRationIsFedByThePantryWhenItRunsDry(GameTestHelper h) {
        Pantry pantry = new Pantry().meals(3, 5, Fare.COOKED);
        Table t = new Table(pantry, 0, 3, 0, 0, 0);
        h.assertTrue(t.ration.food() == 3 + 15, "the food is " + t.ration.food() + ", not the ration's 3 and the pantry's 15");
        int spent = t.ration.spendFood(12, "Raising soldiers");
        h.assertTrue(spent == 12 && pantry.drawn == 2 && Arrays.equals(t.ration.grades(), new int[]{0, 0, 1, 0, 0}) && pantry.meals.size() == 1,
                "12 spent returned " + spent + " after " + pantry.drawn + " meals, leaving " + Arrays.toString(t.ration.grades()) + " and " + pantry.meals.size() + " meals");
        h.assertTrue(t.roads.equals(List.of(HaulLog.LARDER + ">Raising soldiers:12")), "the road was " + t.roads);
        spent = t.ration.spendFood(10);
        h.assertTrue(spent == 6 && t.ration.food() == 0 && pantry.drawn == 3, "10 spent with 6 left returned " + spent + ", food left " + t.ration.food());

        Pantry big = new Pantry().meals(300, 1, Fare.RAW);
        Table b = new Table(big, 0, 0, 0, 0, 0);
        spent = b.ration.spendFood(1000);
        h.assertTrue(spent == Ration.MEALS_AT_MOST && Ration.MEALS_AT_MOST == 256 && big.drawn == 256 && big.meals.size() == 44,
                "a spend of 1000 drew " + big.drawn + " meals and got " + spent + ", not 256 of the 300");
        h.succeed();
    }

    /**
     * The fare is the average grade of what can be eaten, 1 (plain) when there is none: 2 raw and 2 feast make 2.0; the larder's
     * gorge beside them brings it to 3.0; what is held back for the cooks counts only when nothing else is there; and
     * {@code byGrade} adds the ration, the pantry and the held-back.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void theFareGradeIsTheAverageGradeOfWhatCanBeEaten(GameTestHelper h) {
        Table t = new Table(new Pantry(), 0, 2, 0, 2, 0);
        h.assertTrue(t.ration.fareGrade() == 2.0, "2 raw and 2 feast are " + t.ration.fareGrade() + ", not 2.0");
        Pantry gorge = new Pantry().meals(1, 4, Fare.GORGE_GRADE);
        Table g = new Table(gorge, 0, 2, 0, 2, 0);
        h.assertTrue(g.ration.fareGrade() == 3.0, "with 4 gorge in the larder it is " + g.ration.fareGrade() + ", not 3.0");
        Pantry makings = new Pantry();
        makings.held[Fare.COOKED] = 4;
        Table m = new Table(makings, 0, 2, 0, 0, 0);
        h.assertTrue(m.ration.fareGrade() == 1.0, "makings held for the cooks lifted the fare to " + m.ration.fareGrade() + " beside 2 raw");
        Table only = new Table(makings, 0, 0, 0, 0, 0);
        h.assertTrue(only.ration.fareGrade() == 2.0, "with only held-back cooked makings it is " + only.ration.fareGrade() + ", not 2.0");
        Table none = new Table(new Pantry(), 0, 0, 0, 0, 0);
        h.assertTrue(none.ration.fareGrade() == 1.0, "with no food at all it is " + none.ration.fareGrade() + ", not plain (1.0)");
        int[] all = m.ration.byGrade();
        h.assertTrue(Arrays.equals(all, new int[]{0, 2, 4, 0, 0}) && m.ration.food() == 6, "by grade it is " + Arrays.toString(all) + " and the food " + m.ration.food());
        h.succeed();
    }

    /** What a test's famine policy heard. */
    private static final class Heard implements Famine {
        final List<Integer> seconds = new ArrayList<>();
        int fed;

        @Override
        public void starving(ServerLevel level, Settlement settlement, int seconds) {
            this.seconds.add(seconds);
        }

        @Override
        public void fed(ServerLevel level, Settlement settlement) {
            fed++;
        }
    }

    /**
     * Each second the people eat what they owe, a whole point at a time (0.5 a second takes one every second second, on the
     * eaten road), and a settlement with people and no food counts the seconds and tells its famine at 1, 2, 3 and every second
     * after; with the people gone the count stops with nothing said; and when food is back the policy is told once and the
     * count starts again at 1 the next hunger. A settlement that names no policy has none, and its people are those up, in
     * crews and in the reserve.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aRationEatsEachSecondAndTellsItsFamineHowLongItIsHungry(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Table t = new Table(new Pantry(), 0, 3, 0, 0, 0);
        Heard heard = new Heard();
        int[] people = {5};
        StubSettlement stub = new StubSettlement().answer("food", a -> t.ration.food()).answer("population", a -> people[0]).is("famine", heard);
        Settlement s = stub.settlement();
        t.ration.second(level, s, 0.5f);
        h.assertTrue(t.ration.inRation() == 3 && t.roads.isEmpty() && heard.seconds.isEmpty(), "half a point eaten spent " + t.roads + ", ration " + t.ration.inRation());
        t.ration.second(level, s, 0.5f);
        h.assertTrue(t.ration.inRation() == 2 && t.roads.equals(List.of(HaulLog.LARDER + ">" + HaulLog.EATEN + ":1")) && t.ration.hunger() == 0f,
                "a whole point owed spent " + t.roads + ", ration " + t.ration.inRation() + ", hunger " + t.ration.hunger());
        t.ration.spendFood(100);
        t.ration.second(level, s, 0f);
        t.ration.second(level, s, 0f);
        t.ration.second(level, s, 0f);
        h.assertTrue(heard.seconds.equals(List.of(1, 2, 3)) && t.ration.starvingSeconds() == 3 && heard.fed == 0,
                "three hungry seconds told the famine " + heard.seconds + " (fed " + heard.fed + "), counting " + t.ration.starvingSeconds());
        people[0] = 0;
        t.ration.second(level, s, 0f);
        h.assertTrue(heard.seconds.size() == 3 && t.ration.starvingSeconds() == 0 && heard.fed == 0,
                "with no people left the famine heard " + heard.seconds + " (fed " + heard.fed + "), counting " + t.ration.starvingSeconds());
        people[0] = 4;
        t.ration.second(level, s, 0f);
        h.assertTrue(heard.seconds.equals(List.of(1, 2, 3, 1)), "a new hunger did not start at 1: " + heard.seconds);
        t.ration.addFood(5, Fare.RAW, "a gift");
        t.ration.second(level, s, 0f);
        t.ration.second(level, s, 0f);
        h.assertTrue(heard.fed == 1 && heard.seconds.size() == 4 && t.ration.starvingSeconds() == 0,
                "food back told the famine fed " + heard.fed + " times (hungry " + heard.seconds + "), counting " + t.ration.starvingSeconds());
        h.assertTrue(t.roads.contains("a gift>" + HaulLog.LARDER + ":5"), "the gift was not logged: " + t.roads);

        StubSettlement plain = new StubSettlement();
        Works works = new Works(plain.settlement());
        plain.is("works", works).is("peopleCount", 2).is("reserve", 3);
        h.assertTrue(plain.settlement().famine() == Famine.NONE && plain.settlement().population() == 5 + works.crewCount(),
                "a settlement naming no policy has " + plain.settlement().famine() + " and a population of " + plain.settlement().population() + ", not none and 5");
        h.succeed();
    }

    private static int sum(int[] points) {
        return Arrays.stream(points).sum();
    }

    /**
     * A pantry over a larder reads the larder on the level it is asked for, each time it is asked: a barrel of 3 bread in a larder room is
     * edible food and nothing held back, one drawn is a bread (its points the meal's, and the barrel 2 the poorer), and the next
     * count is that much less; with no level yet (null) it holds nothing, has nothing to draw and never asks the larder (a larder
     * that fails any question it is asked is not asked), and once the level is there it reads it.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aPantryOverALarderReadsItOnItsLevelAndHasNothingWithNone(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(2, 3, 2));
        level.setBlock(at, Blocks.BARREL.defaultBlockState(), 3);
        BarrelBlockEntity barrel = (BarrelBlockEntity) level.getBlockEntity(at);
        barrel.setItem(0, new ItemStack(Items.BREAD, 3));
        Building room = new Building("a_larder_room", new BoundingBox(at).inflatedBy(1));
        Works[] works = new Works[1];
        Settlement home = new StubSettlement().answer("buildings", a -> List.of(room)).is("larderRoom", true).answer("works", a -> works[0])
                .answer("foodValue", a -> a[0]).settlement();
        works[0] = new Works(home);
        Larder larder = new Larder(home);

        Ration.Pantry pantry = Ration.Pantry.of(larder, () -> level);
        int before = sum(pantry.edible());
        h.assertTrue(before > 0 && before == larder.food(level) && sum(pantry.held()) == 0 && pantry.edible().length == Fare.GRADES.length,
                "a barrel of 3 bread was " + Arrays.toString(pantry.edible()) + " edible and " + Arrays.toString(pantry.held()) + " held back, the larder's " + larder.food(level));
        Larder.Meal meal = pantry.draw();
        h.assertTrue(meal != null && meal.item() == Items.BREAD && meal.points() > 0 && barrel.getItem(0).getCount() == 2,
                "a draw gave " + meal + " and left " + barrel.getItem(0) + " in the barrel, not a bread and 2");
        h.assertTrue(sum(pantry.edible()) == before - meal.points(), "after the draw it holds " + sum(pantry.edible()) + ", not " + before + " less the meal's " + meal.points());

        Larder untouched = new Larder(new StubSettlement().settlement());
        Ration.Pantry none = Ration.Pantry.of(untouched, () -> null);
        h.assertTrue(none.edible().length == Fare.GRADES.length && none.held().length == Fare.GRADES.length && sum(none.edible()) == 0 && sum(none.held()) == 0 && none.draw() == null,
                "a pantry with no level held " + Arrays.toString(none.edible()) + " and " + Arrays.toString(none.held()));
        ServerLevel[] later = {null};
        Ration.Pantry late = Ration.Pantry.of(larder, () -> later[0]);
        h.assertTrue(sum(late.edible()) == 0 && late.draw() == null, "a pantry asked before its level was there held something");
        later[0] = level;
        h.assertTrue(sum(late.edible()) > 0, "a pantry whose level came held nothing");
        level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
        h.succeed();
    }

    /**
     * What is owed is not spent by owing: 0.4 three times is 1.2 owed and the ration is whole (10 raw stays 10) and nothing is saved
     * or logged; the people eat it a whole point at a time ({@code eat}), one spent on the eaten road and 0.2 owed over, and 2.5 more
     * is owed to two more points and a half over.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aRationOwesWhatItIsToldAndEatsItAWholePointAtATime(GameTestHelper h) {
        Table t = new Table(new Pantry(), 0, 10, 0, 0, 0);
        t.ration.owe(0.4f);
        t.ration.owe(0.4f);
        t.ration.owe(0.4f);
        h.assertTrue(Math.abs(t.ration.hunger() - 1.2f) < 1e-4f && t.ration.inRation() == 10 && t.roads.isEmpty() && t.saves == 0,
                "1.2 owed left hunger " + t.ration.hunger() + ", the ration " + t.ration.inRation() + ", roads " + t.roads + ", saves " + t.saves);
        t.ration.eat(0f);
        h.assertTrue(t.ration.inRation() == 9 && Math.abs(t.ration.hunger() - 0.2f) < 1e-4f && t.roads.equals(List.of(HaulLog.LARDER + ">" + HaulLog.EATEN + ":1")),
                "eating 1.2 spent to " + t.ration.inRation() + " with " + t.ration.hunger() + " owed, roads " + t.roads + ", not 9, 0.2 and one on the eaten road");
        t.ration.owe(2.5f);
        t.ration.eat(0f);
        h.assertTrue(t.ration.inRation() == 7 && Math.abs(t.ration.hunger() - 0.7f) < 1e-4f, "eating 2.7 more spent to " + t.ration.inRation() + " with " + t.ration.hunger() + " owed, not 7 and 0.7");
        h.succeed();
    }

    /**
     * Food of none or less is a spend of that much, whatever grade it is given: -7 of 5 in each grade takes the 5 slop and 2 of the
     * raw (the worst first), logs nothing and saves once; zero spends nothing and saves nothing.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void foodOfNoneOrLessIsASpendOfThatMuchWhateverGradeItIsGiven(GameTestHelper h) {
        Table t = new Table(new Pantry(), 5, 5, 5, 5, 5);
        t.ration.addFood(-7, Fare.GORGE_GRADE, "Hunting");
        h.assertTrue(Arrays.equals(t.ration.grades(), new int[]{0, 3, 5, 5, 5}) && t.roads.isEmpty() && t.saves == 1,
                "-7 of 5 in each grade left " + Arrays.toString(t.ration.grades()) + ", roads " + t.roads + ", saves " + t.saves + ", not 0, 3, 5, 5, 5 with nothing logged and one save");
        t.ration.addFood(0, Fare.RAW, "Hunting");
        h.assertTrue(Arrays.equals(t.ration.grades(), new int[]{0, 3, 5, 5, 5}) && t.roads.isEmpty() && t.saves == 1, "0 changed the ration, logged or saved: " + Arrays.toString(t.ration.grades()) + ", saves " + t.saves);
        h.succeed();
    }

    /**
     * A grade outside the five is clamped to the nearest: 99 is gorge and -7 slop; food comes in on the road it names, and a road of
     * "" is no road, in or out, logging nothing though the food moves and is saved: 3 gorge "Feasting", 2 slop "Scraps", 4 cooked from "",
     * then 2 spent on "" (the slop, the worst grade) leave 4 cooked and 3 gorge, with the two named roads logged and four saves.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void aGradeIsClampedAndAnEmptyRoadIsNoRoad(GameTestHelper h) {
        Table t = new Table(new Pantry(), 0, 0, 0, 0, 0);
        t.ration.addFood(3, 99, "Feasting");
        t.ration.addFood(2, -7, "Scraps");
        t.ration.addFood(4, Fare.COOKED, "");
        h.assertTrue(Arrays.equals(t.ration.grades(), new int[]{2, 0, 4, 0, 3}), "grade 99, -7 and cooked came in as " + Arrays.toString(t.ration.grades()) + ", not 2, 0, 4, 0, 3");
        int spent = t.ration.spendFood(2, "");
        h.assertTrue(spent == 2 && Arrays.equals(t.ration.grades(), new int[]{0, 0, 4, 0, 3}), "2 spent on no road left " + Arrays.toString(t.ration.grades()) + " (spent " + spent + ")");
        h.assertTrue(t.roads.equals(List.of("Feasting>" + HaulLog.LARDER + ":3", "Scraps>" + HaulLog.LARDER + ":2")) && t.saves == 4,
                "the roads were " + t.roads + " and the saves " + t.saves + ", not the two named roads and 4 saves");
        h.succeed();
    }
}
