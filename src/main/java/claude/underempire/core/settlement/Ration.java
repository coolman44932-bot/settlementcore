package claude.underempire.core.settlement;

import claude.underempire.core.food.Fare;
import claude.underempire.core.food.Larder;
import claude.underempire.core.ledger.HaulLog;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * A settlement's ration: the food it has taken out of its larder and not yet eaten, as points by grade (core/food/Fare: slop,
 * raw, cooked, feast, gorge), and the eating of it. Lifted from the Warren Heart's own food code (docs/plans/
 * greatplan-first-spawning.md, section B item 1): the same algorithm, the same constants and the same save keys, so a tag the
 * Heart wrote reads back through here the same, and the Heart can move onto it later (P3.1) without a save changing.
 *
 * <p>The ration is eaten before anything in the larder's containers, worst grade first ({@link #spendFood}); when it runs dry
 * the larder's containers feed it, item by item. Each second ({@link #second}) the people eat what they owe, and a settlement
 * with people and no food at all counts the seconds it has been hungry and hands them to its {@link Famine}.
 *
 * <p>It holds no block entity: the larder is a {@link Pantry}, the haul log a {@link Road}, and a change is a callback.
 */
public final class Ration {

    /** A new settlement starts with this much raw food (a new Warren Heart's 60). */
    public static final int STARTER_RAW = 60;
    /** Most meals one spend takes out of the pantry (the Heart's 256: a spend of a lot never walks the whole larder). */
    public static final int MEALS_AT_MOST = 256;

    /** The larder's containers as the ration sees them: what they hold by grade, and one item out to be eaten. */
    public interface Pantry {
        /** No larder: nothing in reach. */
        Pantry NONE = new Pantry() {
            @Override
            public int[] edible() {
                return new int[Fare.GRADES.length];
            }

            @Override
            public int[] held() {
                return new int[Fare.GRADES.length];
            }

            @Nullable
            @Override
            public Larder.Meal draw() {
                return null;
            }
        };

        /** Food points by grade the settlement may eat from its containers now. */
        int[] edible();

        /** Food points by grade held back for the cooks. */
        int[] held();

        /** One item out of the containers to be eaten, or null when there is none in reach. */
        @Nullable
        Larder.Meal draw();

        /** The larder, read on {@code level} (a level that is null, or not loaded yet, has nothing in reach). */
        static Pantry of(Larder larder, Supplier<ServerLevel> level) {
            return new Pantry() {
                @Override
                public int[] edible() {
                    ServerLevel l = level.get();
                    return l == null ? NONE.edible() : larder.edible(l);
                }

                @Override
                public int[] held() {
                    ServerLevel l = level.get();
                    return l == null ? NONE.held() : larder.held(l);
                }

                @Nullable
                @Override
                public Larder.Meal draw() {
                    ServerLevel l = level.get();
                    return l == null ? null : larder.draw(l);
                }
            };
        }
    }

    /** The haul log's road: {@code n} food points went from one place to another (Settlement#log with no item). */
    @FunctionalInterface
    public interface Road {
        void log(String from, String to, long n);
    }

    private final int[] points = new int[Fare.GRADES.length];
    /** Fractional food owed: each person's share accrues here, and a whole point is spent at a time. */
    private float hunger;
    private int starvingSeconds;
    private final Pantry pantry;
    private final Road road;
    private final Runnable changed;

    /**
     * @param pantry  the larder's containers, behind the ration
     * @param road    where the haul log hears of food in and out
     * @param changed what to do when the ration changed and must be saved (the block entity's setChanged)
     */
    public Ration(Pantry pantry, Road road, Runnable changed) {
        this.pantry = pantry;
        this.road = road;
        this.changed = changed;
        points[Fare.RAW] = STARTER_RAW;
    }

    // ------------------------------------------------------------------ what it holds

    /** The ration's own points, all grades. */
    public int inRation() {
        return Arrays.stream(points).sum();
    }

    /** The ration by grade (a copy). */
    public int[] grades() {
        return points.clone();
    }

    /** All the food: the ration and what the larder's containers hold (the cooks' held-back makings too). */
    public int food() {
        int n = inRation();
        for (int g : pantry.edible()) {
            n += g;
        }
        for (int g : pantry.held()) {
            n += g;
        }
        return n;
    }

    /** All the food by grade (a copy): the ration, and the larder's containers' edible and held-back together. */
    public int[] byGrade() {
        int[] out = points.clone();
        int[] e = pantry.edible(), h = pantry.held();
        for (int g = 0; g < out.length; g++) {
            out[g] += e[g] + h[g];
        }
        return out;
    }

    /**
     * The larder's average grade, 0 (all slop) to 4 (all gorge); plain (1) when it is empty. The raw makings held back for the
     * cooks count only when there is nothing else.
     */
    public double fareGrade() {
        int[] by = points.clone();
        int[] e = pantry.edible();
        for (int g = 0; g < by.length; g++) {
            by[g] += e[g];
        }
        if (Arrays.stream(by).sum() == 0) {
            by = pantry.held();
        }
        int n = Arrays.stream(by).sum();
        double sum = 0;
        for (int g = 0; g < by.length; g++) {
            sum += (double) g * by[g];
        }
        return n <= 0 ? 1.0 : sum / n;
    }

    // ------------------------------------------------------------------ food in and out

    /** Food of a grade into the ration, from {@code from} (the haul log's road; "" is not logged). Zero or less is a spend of that much. */
    public void addFood(int n, int grade, String from) {
        if (n <= 0) {
            spendFood(-n);
            return;
        }
        points[Math.max(0, Math.min(points.length - 1, grade))] += n;
        road.log(from, HaulLog.LARDER, n);
        changed.run();
    }

    /** Food spent on {@code on} (the haul log's road). Returns how much there was to spend. */
    public int spendFood(int n, String on) {
        int spent = spendFood(n);
        road.log(HaulLog.LARDER, on, spent);
        return spent;
    }

    /**
     * Food eaten or spent: the ration first, then item by item out of the larder's containers, the worst first (the slop goes
     * to the slaves), so better food lifts the fare as soon as the slop is gone. Returns how much there was to spend.
     */
    public int spendFood(int n) {
        int spent = fromRation(n);
        for (int meals = 0; spent < n && meals < MEALS_AT_MOST; meals++) {
            Larder.Meal m = pantry.draw();
            if (m == null) {
                break;
            }
            points[Math.max(0, Math.min(points.length - 1, m.grade()))] += m.points();
            spent += fromRation(n - spent);
        }
        if (spent > 0) {
            changed.run();
        }
        return spent;
    }

    /** Up to {@code n} points out of the ration alone, the worst grade first. */
    private int fromRation(int n) {
        int spent = 0;
        for (int g = 0; g < points.length && spent < n; g++) {
            int take = Math.min(points[g], n - spent);
            points[g] -= take;
            spent += take;
        }
        return spent;
    }

    // ------------------------------------------------------------------ eating

    /** {@code points} more food owed to the people (a healing's cost): eaten with the rest, a whole point at a time. */
    public void owe(float points) {
        hunger += points;
    }

    /** The people eat {@code owed} points (a night slept through: the seconds' worth at once): what is owed in whole points is spent. */
    public void eat(float owed) {
        hunger += owed;
        while (hunger >= 1f) {
            hunger -= 1f;
            spendFood(1, HaulLog.EATEN);
        }
    }

    /** Fractional food still owed. */
    public float hunger() {
        return hunger;
    }

    /** How many seconds in a row the settlement has had people and no food. */
    public int starvingSeconds() {
        return starvingSeconds;
    }

    /**
     * One second of the settlement's life at the table: the people eat {@code eatsPerSecond} points, and a settlement that has
     * no food and some people (its {@link Settlement#population()}) counts the second and hands the count to its
     * {@link Settlement#famine()}: 1 the first second, and every second after. When food is back the count stops and the
     * policy hears {@link Famine#fed}; with no people left it stops too, with nothing said.
     */
    public void second(ServerLevel level, Settlement settlement, float eatsPerSecond) {
        eat(eatsPerSecond);
        int food = settlement.food();
        if (food <= 0 && settlement.population() > 0) {
            starvingSeconds++;
            settlement.famine().starving(level, settlement, starvingSeconds);
        } else if (starvingSeconds > 0) {
            starvingSeconds = 0;
            if (food > 0) {
                settlement.famine().fed(level, settlement);
            }
        }
    }

    // ------------------------------------------------------------------ saved

    /** Written under the Heart's own keys: {@code food} (the total, for a reader of the old kind), {@code larder} (the ration by grade), {@code hunger} and {@code starving}. */
    public void save(CompoundTag tag) {
        tag.putInt("food", inRation());
        tag.putIntArray("larder", points.clone());              // the ration (its old name: a larder from before the barrels loads into it)
        tag.putFloat("hunger", hunger);
        tag.putInt("starving", starvingSeconds);
    }

    /** Read from the keys {@link #save} writes: four piles from before the gorge grade load with none of that, and a larder from before the fare is all raw. */
    public void load(CompoundTag tag) {
        int[] saved = tag.getIntArray("larder");
        Arrays.fill(points, 0);
        if (saved.length >= 4) {
            System.arraycopy(saved, 0, points, 0, Math.min(saved.length, points.length));
        } else {
            points[Fare.RAW] = tag.getInt("food");
        }
        hunger = tag.getFloat("hunger");
        starvingSeconds = tag.getInt("starving");
    }
}
