package claude.settlementcore.work;

import claude.settlementcore.food.Fare;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Cook-Pits' cookbook (2026-09-25: the user asked for the cooks to make Pam's HarvestCraft's dishes). Every
 * dish the server's recipes can make at a crafting grid (vanilla's, Warren Stew, Pam's ~1,300: any food the
 * fare grades cooked or a feast), and every step that makes a dish's makings (flour, dough, toast, fried
 * meat: crafting, or a furnace, smoker or campfire), worked out once per datapack reload.
 *
 * <p>{@link #plan} is what the cooks ask: the best dish (a gorge before a feast before cooked fare, then the
 * most filling) the Pantry has everything for; or, when the best it could make lacks up to three of its
 * makings, the first step toward them (up to four steps deep: a chili dog's hot dog, its bread and its
 * mayonnaise). Pam's cooking tools (a pot, a skillet, a cutting board...) are kept: back in the Pantry after
 * use. Water is drawn from the room's cauldron. A feast goes into a dish only if that dish is a gorge.
 */
final class KitchenBook {

    /** One recipe the kitchen can do: what each of its ingredients takes, what it makes, how, and where. */
    record Step(Identifier id, List<Set<Item>> slots, ItemStack result, String how, String at, int grade, boolean fire) {
        String what() {
            return how + " " + result.getHoverName().getString();
        }
    }

    /**
     * What to do: the step, the item used for each slot it consumes, the tools it keeps, whether it is a dish
     * (into the Cooked Fare) or makings (back into the Pantry for the dish it is for).
     */
    record Plan(@Nullable Step step, List<Item> use, List<Item> keep, boolean dish, @Nullable Step forDish) {
        /** The best dish is short only of bowls: CookWork carves them from the Pantry's timber. */
        static Plan carve(Step forDish) {
            return new Plan(null, List.of(), List.of(), false, forDish);
        }

        boolean carving() {
            return step == null;
        }
    }

    static final TagKey<Item> WATER = TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", "water"));
    static final TagKey<Item> TOOL_POT = tag("tool_pot"), TOOL_SAUCEPAN = tag("tool_saucepan"), TOOL_SKILLET = tag("tool_skillet"),
            TOOL_BAKEWARE = tag("tool_bakeware");

    private static TagKey<Item> tag(String id) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("c", id));
    }

    /** How many steps down a dish's makings are looked for (a gorge's feasts, their makings, and theirs). */
    static final int DEPTH = 4;
    /** How many of a dish's makings may be missing for the cooks to make them first (a Pam's burger lacks three). */
    static final int MISSING = 3;
    /** A step toward a dish is not taken while the Pantry already holds this many of what it makes. */
    static final int MAKINGS_KEEP = 8;
    /**
     * The search's bounds, for the server's sake (the cooks plan every second): tries in all, tries on any one
     * dish (a dish of feasts that cannot quite be made must not starve the rest), and time.
     */
    static final int TRIES = 50000, TRIES_PER_DISH = 4000;
    static final long PLAN_NANOS = 15_000_000L;
    /**
     * The same time counted in steps instead ({@link #steps}: recipes matched against the Pantry), when
     * -Dsettlementcore.deterministic is set (the gameTestServer run sets it, but not with -Pperf), as clan/ScentMap
     * does. The GameTest server ticks flat out, and on the clock a busy PC ran a plan past its time and the cooks
     * planned something else: a stocked Pantry's plan, 2 ms alone, took 139 ms in a loaded -Pcompat run and
     * planned frying a BLT instead of the makings for a better dish. {@value} steps is about what 15 ms does on
     * the user's PC (2026-09-26, -Pcompat, a Pantry of 162 kinds): a plan costs 0.3-0.5 ms to see what can be
     * made, then about 7 µs a step.
     */
    static final int PLAN_STEPS = 2000;
    private static final boolean STEPPED = Boolean.getBoolean("settlementcore.deterministic");

    /**
     * The work every plan has done so far, for the tests (which count it rather than time it): steps, each a
     * recipe matched against the Pantry (a dish, or a way to its makings), and of those the ways tried.
     */
    long steps, tries;

    final List<Step> dishes;
    final Map<Item, List<Step>> makers;
    final Set<Item> wanted;
    final Set<Item> tools;
    final Set<Item> water;

    private KitchenBook(List<Step> dishes, Map<Item, List<Step>> makers, Set<Item> wanted, Set<Item> tools, Set<Item> water) {
        this.dishes = dishes;
        this.makers = makers;
        this.wanted = wanted;
        this.tools = tools;
        this.water = water;
    }

    private static KitchenBook book;
    private static RecipeManager bookFor;

    static synchronized KitchenBook of(ServerLevel level) {
        RecipeManager rm = level.getRecipeManager();
        if (book == null || rm != bookFor) {
            book = build(rm.getRecipes(), level.registryAccess());
            bookFor = rm;
        }
        return book;
    }

    static KitchenBook build(java.util.Collection<RecipeHolder<?>> recipes, RegistryAccess access) {
        Set<Item> water = new HashSet<>();
        for (var h : BuiltInRegistries.ITEM.getTagOrEmpty(WATER)) {
            if (new ItemStack(h.value()).getCraftingRemainingItem().isEmpty()) {
                water.add(h.value());                            // fresh water, not a bucket of it
            }
        }
        Set<Item> tools = new HashSet<>();
        Map<Item, Integer> usedBy = new HashMap<>();             // how many crafting recipes of any kind use each item
        List<Step> all = new ArrayList<>();
        for (RecipeHolder<?> h : recipes) {
            Recipe<?> r = h.value();
            RecipeType<?> t = r.getType();
            boolean fire = t == RecipeType.SMELTING || t == RecipeType.SMOKING || t == RecipeType.CAMPFIRE_COOKING;
            if (t != RecipeType.CRAFTING && !fire) {
                continue;
            }
            ItemStack out;
            try {
                out = r.getResultItem(access);
            } catch (RuntimeException e) {
                continue;
            }
            if (out == null || out.isEmpty()) {
                continue;
            }
            List<Set<Item>> slots = new ArrayList<>();
            boolean fillable = true;
            for (Ingredient ing : r.getIngredients()) {
                if (ing.isEmpty()) {
                    continue;
                }
                Set<Item> takes = new HashSet<>();
                for (ItemStack st : ing.getItems()) {
                    if (!st.is(Items.BARRIER)) {
                        takes.add(st.getItem());
                    }
                }
                if (takes.isEmpty()) {
                    fillable = false;                            // an empty tag: nothing fills it (Pam's crops not installed)
                    break;
                }
                slots.add(takes);
                if (t == RecipeType.CRAFTING) {
                    for (Item i : takes) {
                        usedBy.merge(i, 1, Integer::sum);
                        ItemStack rem = new ItemStack(i).getCraftingRemainingItem();
                        if (!rem.isEmpty() && rem.is(i)) {
                            tools.add(i);                        // it comes back out of the grid: a cooking tool
                        }
                    }
                }
            }
            if (!fillable || slots.isEmpty()) {
                continue;
            }
            if (slots.stream().distinct().count() == 1 && out.getCount() > 1 && t == RecipeType.CRAFTING) {
                continue;                                        // a crate opened or a food cut up: not cooking
            }
            all.add(new Step(h.id(), slots, out.copy(), how(t, slots, out), station(t, slots, out), Fare.grade(out), fire));
        }
        // the dishes: food made at a grid that is at least cooked fare, and is not a tool itself
        List<Step> dishes = new ArrayList<>();
        Map<Item, List<Step>> makersAll = new HashMap<>();
        for (Step s : all) {
            if (tools.contains(s.result().getItem())) {
                continue;
            }
            makersAll.computeIfAbsent(s.result().getItem(), k -> new ArrayList<>()).add(s);
            if (!s.fire() && s.grade() >= Fare.COOKED && !s.result().is(Tags.Items.FOODS_GOLDEN)) {
                dishes.add(s);
            }
        }
        dishes.sort(Comparator.comparingInt((Step s) -> -s.grade()).thenComparingInt(s -> -Fare.nutrition(s.result()) * s.result().getCount())
                .thenComparing(s -> s.id().toString()));
        // the makings: what makes what the dishes need, and what that needs, DEPTH deep
        Map<Item, List<Step>> makers = new HashMap<>();
        Set<Item> need = new HashSet<>();
        for (Step d : dishes) {
            d.slots().forEach(need::addAll);
        }
        Set<Step> kitchen = new HashSet<>(dishes);
        for (int depth = 0; depth < DEPTH; depth++) {
            Set<Item> next = new HashSet<>();
            for (Item i : need) {
                if (tools.contains(i) || water.contains(i) || makers.containsKey(i)) {
                    continue;
                }
                List<Step> ms = makersAll.get(i);
                if (ms == null) {
                    continue;
                }
                makers.put(i, ms);
                for (Step m : ms) {
                    if (kitchen.add(m)) {
                        m.slots().forEach(next::addAll);
                    }
                }
            }
            need = next;
        }
        // what the Pantry wants: food no better than cooked that the kitchen uses, and whatever else it uses more
        // than the rest of the recipes do (sugar, eggs, milk, flour, bowls: not iron, planks or gold)
        Map<Item, Integer> usedByKitchen = new HashMap<>();
        for (Step s : kitchen) {
            for (Set<Item> slot : s.slots()) {
                for (Item i : slot) {
                    usedByKitchen.merge(i, 1, Integer::sum);
                }
            }
        }
        Set<Item> wanted = new HashSet<>();
        for (var e : usedByKitchen.entrySet()) {
            Item i = e.getKey();
            if (tools.contains(i) || water.contains(i)) {
                continue;
            }
            int g = Fare.grade(new ItemStack(i));
            if (g >= 0 ? g <= Fare.COOKED : e.getValue() * 2 >= usedBy.getOrDefault(i, 0)) {
                wanted.add(i);
            }
        }
        return new KitchenBook(List.copyOf(dishes), makers, wanted, tools, water);
    }

    private static String how(RecipeType<?> t, List<Set<Item>> slots, ItemStack out) {
        if (t == RecipeType.CAMPFIRE_COOKING) {
            return "roasting";
        }
        if (t == RecipeType.SMOKING) {
            return "smoking";
        }
        if (t == RecipeType.SMELTING) {
            return "baking";
        }
        return switch (station(t, slots, out)) {
            case "pot" -> "boiling";
            case "oven" -> "baking";
            case "fire" -> "frying";
            default -> "making";
        };
    }

    /** Where it is made: the pits, the stew-cauldron, the bread oven, or the bench, by its tools or what it is. */
    private static String station(RecipeType<?> t, List<Set<Item>> slots, ItemStack out) {
        if (t == RecipeType.CAMPFIRE_COOKING) {
            return "fire";
        }
        if (t != RecipeType.CRAFTING) {
            return "oven";
        }
        for (Set<Item> slot : slots) {
            for (Item i : slot) {
                ItemStack s = new ItemStack(i);
                if (s.is(TOOL_SKILLET)) {
                    return "fire";
                }
                if (s.is(TOOL_POT) || s.is(TOOL_SAUCEPAN)) {
                    return "pot";
                }
                if (s.is(TOOL_BAKEWARE)) {
                    return "oven";
                }
            }
        }
        if (Fare.leftover(out).is(Items.BOWL)) {
            return "pot";
        }
        if (out.is(Tags.Items.FOODS_BREAD) || out.is(Tags.Items.FOODS_PIE) || out.is(Tags.Items.FOODS_COOKIE)
                || out.is(Tags.Items.FOODS_EDIBLE_WHEN_PLACED)) {
            return "oven";
        }
        return "bench";
    }

    /** Whether the Pantry should be stocked with this. */
    boolean wants(ItemStack s) {
        return wanted.contains(s.getItem());
    }

    // ------------------------------------------------------------------ planning

    /**
     * The best thing to do with {@code pantry} (item -> how many), with water to draw if {@code drawWater}, and
     * timber to carve bowls from if {@code canCarve}.
     */
    @Nullable
    Plan plan(Map<Item, Integer> pantry, boolean drawWater, boolean canCarve) {
        return plan(pantry, drawWater, canCarve, null);
    }

    /**
     * As {@link #plan(Map, boolean, boolean)}, but the dish the cooks are already making the makings for
     * ({@code working}) comes first while it can still be made: a cook does not leave a burger half-made for a
     * better idea, and the flour it ground go to waste.
     */
    @Nullable
    Plan plan(Map<Item, Integer> pantry, boolean drawWater, boolean canCarve, @Nullable Identifier working) {
        int[] budget = {TRIES};
        long deadline = deadline();
        Reach can = reach(pantry, drawWater, canCarve);
        if (working != null) {
            for (Step d : dishes) {
                if (d.id().equals(working)) {
                    Plan p = attempt(d, pantry, drawWater, canCarve, can, budget, deadline);
                    if (p != null) {
                        return p;
                    }
                    break;
                }
            }
        }
        for (Step d : dishes) {
            Plan p = attempt(d, pantry, drawWater, canCarve, can, budget, deadline);
            if (p != null) {
                return p;
            }
        }
        return null;
    }

    /** When a plan starting now must stop: {@link #PLAN_NANOS} on the clock, or {@link #PLAN_STEPS} steps on. */
    private long deadline() {
        return STEPPED ? steps + PLAN_STEPS : System.nanoTime() + PLAN_NANOS;
    }

    private boolean late(long deadline) {
        return STEPPED ? steps > deadline : System.nanoTime() > deadline;
    }

    /** The way to one dish: itself if the Pantry has everything, bowls carved if that is all it lacks, else the first of its makings. */
    @Nullable
    private Plan attempt(Step d, Map<Item, Integer> pantry, boolean drawWater, boolean canCarve, Reach can, int[] budget, long deadline) {
        if (!maybe(d, pantry, drawWater, can)) {
            return null;
        }
        Match m = match(d, pantry, drawWater);
        if (m.missing.isEmpty()) {
            return new Plan(d, m.use, m.keep, true, null);
        }
        if (canCarve && m.missing.stream().allMatch(sl -> sl.contains(Items.BOWL))) {
            return Plan.carve(d);
        }
        if (m.missing.size() <= MISSING && budget[0] > 0) {
            int slice = Math.min(budget[0], TRIES_PER_DISH);
            int[] mine = {slice};
            Way w = makings(d, m, pantry, drawWater, canCarve, can, DEPTH, mine, deadline);
            budget[0] -= slice - Math.max(0, mine[0]);
            if (late(deadline)) {
                budget[0] = 0;                                   // out of time: what is found stands, the rest waits a second
            }
            if (w != null && w.first() != null) {
                return w.first();
            }
        }
        return null;
    }

    /**
     * What the Pantry has or could be made into within {@link #DEPTH} steps (quantities aside): {@code can}, and
     * for everything some step makes, the fewest steps it takes from the Pantry ({@code make}: 1, its makings
     * all there; 2, one of them made first...). The dishes are first sifted by it, and the search for makings
     * tries the nearest way first and never one deeper than the steps it has left.
     */
    private record Reach(Set<Item> can, Map<Item, Integer> make) {
        boolean has(Item i) {
            return can.contains(i);
        }

        /** Fewest steps to make it, or a lot if it cannot be made within DEPTH. */
        int steps(Item i) {
            return make.getOrDefault(i, DEPTH + 1);
        }
    }

    private Reach reach(Map<Item, Integer> pantry, boolean drawWater, boolean canCarve) {
        Set<Item> can = new HashSet<>();
        pantry.forEach((i, n) -> {
            if (n > 0) {
                can.add(i);
            }
        });
        if (drawWater) {
            can.addAll(water);
        }
        if (canCarve) {
            can.add(Items.BOWL);
        }
        Map<Item, Integer> make = new HashMap<>();
        for (int depth = 1; depth <= DEPTH; depth++) {
            List<Item> got = new ArrayList<>();
            for (List<Step> ms : makers.values()) {
                for (Step m : ms) {
                    Item out = m.result().getItem();
                    if (!make.containsKey(out) && !got.contains(out) && m.slots().stream().allMatch(sl -> sl.stream().anyMatch(can::contains))) {
                        got.add(out);
                    }
                }
            }
            if (got.isEmpty()) {
                break;
            }
            for (Item out : got) {
                make.put(out, depth);
                can.add(out);
            }
        }
        return new Reach(can, make);
    }

    /** How many steps a step's own makings are away at best: the slot hardest to fill, by its nearest item (0: all in the Pantry). */
    private static int depthOf(Step m, Map<Item, Integer> pantry, Reach can) {
        int worst = 0;
        for (Set<Item> slot : m.slots()) {
            int best = DEPTH + 1;
            for (Item i : slot) {
                best = Math.min(best, pantry.getOrDefault(i, 0) > 0 ? 0 : can.steps(i));
                if (best == 0) {
                    break;
                }
            }
            worst = Math.max(worst, best);
        }
        return worst;
    }

    /** Whether a dish could be made from what can be had, lacking no more than {@link #MISSING} of its makings now. */
    private boolean maybe(Step d, Map<Item, Integer> pantry, boolean drawWater, Reach can) {
        int missing = 0;
        for (Set<Item> slot : d.slots()) {
            boolean now = false, ever = false;
            for (Item i : slot) {
                if (pantry.getOrDefault(i, 0) > 0 || drawWater && water.contains(i)) {
                    now = true;
                    break;
                }
                ever |= can.has(i);
            }
            if (!now && (!ever || ++missing > MISSING)) {
                return false;
            }
        }
        return true;
    }

    private record Match(List<Item> use, List<Item> keep, List<Set<Item>> missing, Map<Item, Integer> left) {
    }

    /**
     * Fill a step's slots from the Pantry: the most particular slots first, each with what the Pantry has most of
     * (a food no better than cooked, or a feast for a gorge: a feast is not cooked down), tools kept, water
     * drawn. What could not be filled is {@code missing}; {@code left} is the Pantry after.
     */
    private Match match(Step d, Map<Item, Integer> pantry, boolean drawWater) {
        steps++;
        Map<Item, Integer> left = new HashMap<>(pantry);
        List<Item> use = new ArrayList<>(), keep = new ArrayList<>();
        List<Set<Item>> missing = new ArrayList<>();
        List<Set<Item>> order = new ArrayList<>(d.slots());
        order.sort(Comparator.comparingInt(Set::size));
        for (Set<Item> slot : order) {
            if (drawWater && slot.stream().anyMatch(water::contains)) {
                continue;                                        // drawn from the cauldron
            }
            Item tool = slot.stream().filter(tools::contains).filter(i -> pantry.getOrDefault(i, 0) > 0).findFirst().orElse(null);
            if (tool != null) {
                if (!keep.contains(tool)) {
                    keep.add(tool);
                }
                continue;
            }
            Item best = null;
            int bestN = 0;
            for (Item i : slot) {
                int n = left.getOrDefault(i, 0);
                int g = Fare.grade(new ItemStack(i));
                if (n > bestN && !tools.contains(i) && (g < Fare.FEAST_GRADE || g < d.grade())) {
                    best = i;
                    bestN = n;
                }
            }
            if (best == null) {
                missing.add(slot);
            } else {
                use.add(best);
                left.merge(best, -1, Integer::sum);
            }
        }
        return new Match(use, keep, missing, left);
    }

    /** A way to a dish's makings: its first step, and what the Pantry would hold after all of them. */
    private record Way(@Nullable Plan first, Map<Item, Integer> left) {
    }

    /**
     * The first step toward a dish's missing makings, or null if some of them cannot be made from the Pantry
     * within {@code depth} steps. Every step's ingredients are counted against the Pantry as it would be after
     * the steps before (one egg cannot make both the mayonnaise and the fried egg), and a way that leaves a later
     * making short is given up for the next one (the tomato the ketchup took was the chili's). Bowls are carved
     * from the Pantry's timber (CookWork's own step), last. Every step is for {@code dish}: the cooks keep working
     * toward it, not toward the feast made on the way (a chili dog's hot dog).
     */
    @Nullable
    private Way makings(Step dish, Match m, Map<Item, Integer> pantry, boolean drawWater, boolean canCarve, Reach can, int depth,
                        int[] budget, long deadline) {
        return fill(new Search(dish, pantry, drawWater, canCarve, can, budget, deadline), m.missing(), 0, m.left(), null, depth,
                (l, f) -> new Way(f, l));
    }

    /** One search for a dish's makings: what it is for, what it has to go on, and how long. */
    private record Search(Step dish, Map<Item, Integer> pantry, boolean drawWater, boolean canCarve, Reach can, int[] budget, long deadline) {
    }

    /** What comes after a making is made: the rest of the makings, given the Pantry then and the first step so far. */
    private interface Then {
        @Nullable
        Way go(Map<Item, Integer> left, @Nullable Plan first);
    }

    /** Fill {@code missing} from slot {@code k} on, each way in turn (nearest first) until the rest ({@code then}) works out too. */
    @Nullable
    private Way fill(Search q, List<Set<Item>> missing, int k, Map<Item, Integer> left, @Nullable Plan first, int depth, Then then) {
        if (k == missing.size()) {
            return then.go(left, first);
        }
        Set<Item> slot = missing.get(k);
        if (slot.contains(Items.BOWL)) {
            return q.canCarve() ? fill(q, missing, k + 1, left, first, depth, then) : null;   // carved after the rest
        }
        // what could fill the slot, nearest first: none that takes more steps than are left, or that the Pantry has plenty of
        List<Item> options = new ArrayList<>();
        for (Item i : slot) {
            if (q.can().steps(i) <= depth && q.pantry().getOrDefault(i, 0) < MAKINGS_KEEP) {
                options.add(i);
            }
        }
        options.sort(Comparator.comparingInt(q.can()::steps));
        for (Item i : options) {
            List<Step> ways = new ArrayList<>();
            for (Step maker : makers.getOrDefault(i, List.of())) {
                if (depthOf(maker, q.pantry(), q.can()) < depth) {
                    ways.add(maker);                             // its own makings are near enough
                }
            }
            ways.sort(Comparator.comparingInt(mk -> depthOf(mk, q.pantry(), q.can())));
            for (Step maker : ways) {
                if (--q.budget()[0] <= 0 || (q.budget()[0] & 255) == 0 && late(q.deadline())) {
                    q.budget()[0] = 0;
                    return null;
                }
                tries++;
                Match mm = match(maker, left, q.drawWater());
                Plan here = new Plan(maker, mm.use(), mm.keep(), false, q.dish());
                Way w;
                if (mm.missing().isEmpty()) {
                    w = fill(q, missing, k + 1, mm.left(), first != null ? first : here, depth, then);
                } else if (depth > 1 && mm.missing().size() <= MISSING) {
                    // its own makings first, then it, then the rest of these
                    w = fill(q, mm.missing(), 0, mm.left(), first, depth - 1,
                            (l2, f2) -> fill(q, missing, k + 1, l2, f2 != null ? f2 : here, depth, then));
                } else {
                    continue;
                }
                if (w != null) {
                    return w;
                }
                if (q.budget()[0] <= 0) {
                    return null;
                }
            }
        }
        return null;
    }

    /** Why a dish is or is not what the cooks make (for the tests): its rank, what it lacks, the first step toward it, and what is planned. */
    String why(Map<Item, Integer> pantry, boolean drawWater, boolean canCarve, Item dish) {
        long steps0 = steps, tries0 = tries;
        Plan p = plan(pantry, drawWater, canCarve);
        String planned = (p == null ? "nothing" : p.carving() ? "carving bowls" : p.step().what() + (p.dish() ? "" : " (makings)"))
                + " in " + (steps - steps0) + " steps, " + (tries - tries0) + " tries";
        int rank = 0;
        for (Step d : dishes) {
            rank++;
            if (d.result().getItem() != dish) {
                continue;
            }
            Reach can = reach(pantry, drawWater, canCarve);
            Match m = match(d, pantry, drawWater);
            int[] budget = {TRIES_PER_DISH};
            Way w = m.missing().isEmpty() ? null : makings(d, m, pantry, drawWater, canCarve, can, DEPTH, budget, deadline());
            Plan first = w == null ? null : w.first();
            return d.id() + " rank " + rank + "/" + dishes.size() + ", grade " + d.grade() + ", could be had " + maybe(d, pantry, drawWater, can)
                    + ", lacks " + m.missing().size() + ", first step " + (first == null ? "none" : first.carving() ? "carving" : first.step().what())
                    + " (search " + (TRIES_PER_DISH - budget[0]) + " tries); planned: " + planned;
        }
        return dish + " is not among the " + dishes.size() + " dishes; planned: " + planned;
    }

    /** What the Pantry holds, item by item. */
    static Map<Item, Integer> contents(ServerLevel level, List<net.minecraft.core.BlockPos> pantry) {
        Map<Item, Integer> m = new LinkedHashMap<>();
        for (ItemStack s : Bins.stacks(level, pantry)) {
            if (!s.isEmpty()) {
                m.merge(s.getItem(), s.getCount(), Integer::sum);
            }
        }
        return m;
    }
}
