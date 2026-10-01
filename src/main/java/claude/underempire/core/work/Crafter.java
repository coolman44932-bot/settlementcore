package claude.underempire.core.work;

import claude.underempire.core.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Warlock Engineer's craft (user, 2026-09-26: "can we automate weapon making yet", then "yeah do it"): how to make one
 * of something out of what the Clan Hoard holds, by any crafting-table recipe the world knows (vanilla's, the mod's, any
 * other mod's), making what it lacks on the way out of what it has (sticks from planks from logs, ingots out of a block)
 * up to {@link #DEPTH} deep; and then making it: the makings out of the Hoard's barrels (warpstone out of the Heart's
 * hoard), each craft done as a crafting table does it, the thing made and whatever was left over (the planks a log gave
 * beyond the sticks, the bucket a cake leaves) handed back.
 *
 * <p>Only the Clan Hoard is drawn on, never the Warlord's own chests nor a room's bins. Nothing worn, enchanted or named
 * is ever used up as a making: the Warlord's own gear is not scrap.
 */
public final class Crafter {

    /** How deep it makes the makings of the makings (a halberd's sticks from planks from logs is 2). */
    static final int DEPTH = 3;
    /** Planning steps one plan may take, at most (a tag's many kinds, each tried). */
    static final int BUDGET = 3000;

    private Crafter() {
    }

    // ------------------------------------------------------------------ the recipe book

    private static RecipeManager indexed;
    private static Map<Item, List<RecipeHolder<CraftingRecipe>>> byResult = Map.of();

    /** Every crafting-table recipe that makes {@code item} (special ones, a firework's or a map's, left out). */
    public static List<RecipeHolder<CraftingRecipe>> recipesFor(ServerLevel level, Item item) {
        RecipeManager rm = level.getRecipeManager();
        synchronized (Crafter.class) {
            if (rm != indexed) {
                Map<Item, List<RecipeHolder<CraftingRecipe>>> m = new IdentityHashMap<>();
                for (RecipeHolder<CraftingRecipe> r : rm.getAllRecipesFor(RecipeType.CRAFTING)) {
                    if (r.value().isSpecial() || r.value().getIngredients().isEmpty()) {
                        continue;
                    }
                    ItemStack out = r.value().getResultItem(level.registryAccess());
                    if (!out.isEmpty()) {
                        m.computeIfAbsent(out.getItem(), k -> new ArrayList<>()).add(r);
                    }
                }
                byResult = m;
                indexed = rm;
            }
            return byResult.getOrDefault(item, List.of());
        }
    }

    /** Whether any crafting-table recipe makes {@code like} (its item and its components). */
    public static boolean craftable(ServerLevel level, ItemStack like) {
        for (RecipeHolder<CraftingRecipe> r : recipesFor(level, like.getItem())) {
            if (ItemStack.isSameItemSameComponents(r.value().getResultItem(level.registryAccess()), like)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ what the Hoard holds

    /** One kind of thing, whatever the count: equal when the item and its components are. */
    public record Key(ItemStack stack) {
        public static Key of(ItemStack s) {
            return new Key(s.copyWithCount(1));
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && ItemStack.isSameItemSameComponents(stack, k.stack);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashItemAndComponents(stack);
        }
    }

    /** What the Warlock may draw on: the Hoard's barrels, and the Heart's warpstone as shards. */
    public static Map<Key, Integer> stock(ServerLevel level, Settlement heart, List<BlockPos> hoard) {
        Map<Key, Integer> out = new LinkedHashMap<>();
        for (BlockPos p : hoard) {
            Container c = Bins.at(level, p);
            if (c == null) {
                continue;
            }
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack s = c.getItem(i);
                if (!s.isEmpty()) {
                    out.merge(Key.of(s), s.getCount(), Integer::sum);
                }
            }
        }
        if (heart.currency() > 0) {
            out.merge(Key.of(new ItemStack(heart.currencyItem())), heart.currency(), Integer::sum);
        }
        return out;
    }

    /** Whether a thing may be used up as a making: nothing worn, enchanted or named (the Warlord's gear is not scrap). */
    static boolean usable(ItemStack s) {
        return !s.isDamaged() && !s.isEnchanted() && !s.has(DataComponents.CUSTOM_NAME);
    }

    // ------------------------------------------------------------------ the plan

    /** One craft: the recipe and what goes in each cell of its grid (one each; empty cells empty). */
    public record Step(RecipeHolder<CraftingRecipe> recipe, List<ItemStack> grid, int width, int height) {
    }

    /**
     * How to make one: the crafts in order (the makings' first, the thing itself last); or, if it cannot be made, what
     * the Hoard is short of ("2 Iron Ingot", "Stick"), for the Warlord.
     */
    public record Plan(List<Step> steps, List<String> short_) {
        public boolean ok() {
            return !steps.isEmpty();
        }

        /** "2 Iron Ingot, Stick": what the Hoard is short of. */
        public String shortOf() {
            return String.join(", ", short_);
        }
    }

    /** How to make one of {@code target} out of {@code stock} (not changed), by whichever of its recipes can. */
    public static Plan plan(ServerLevel level, Map<Key, Integer> stock, ItemStack target) {
        List<String> best = null;
        boolean any = false;
        for (RecipeHolder<CraftingRecipe> r : recipesFor(level, target.getItem())) {
            if (!ItemStack.isSameItemSameComponents(r.value().getResultItem(level.registryAccess()), target)) {
                continue;
            }
            any = true;
            Planner p = new Planner(level, new HashMap<>(stock));
            List<Step> steps = new ArrayList<>();
            Set<Item> making = new HashSet<>();
            making.add(target.getItem());
            if (p.craft(r, 0, making, steps, true)) {
                return new Plan(steps, List.of());
            }
            List<String> s = p.shortList();
            if (best == null || s.size() < best.size()) {
                best = s;
            }
        }
        return new Plan(List.of(), any && best != null ? best : List.of());
    }

    private static final class Planner {
        final ServerLevel level;
        final Map<Key, Integer> stock;
        int budget = BUDGET;
        /** What the thing's own recipe could not find, by name: how many of each. */
        final Map<String, Integer> missing = new LinkedHashMap<>();

        Planner(ServerLevel level, Map<Key, Integer> stock) {
            this.level = level;
            this.stock = stock;
        }

        List<String> shortList() {
            List<String> out = new ArrayList<>();
            for (var e : missing.entrySet()) {
                out.add(e.getValue() > 1 ? e.getValue() + " " + e.getKey() : e.getKey());
            }
            return out;
        }

        /**
         * Plan one craft of {@code r}: each of its makings found in the stock or made on the way, the craft added to
         * {@code steps} and what it makes put into the stock. {@code report}: the thing's own recipe, whose shortfalls
         * are named for the Warlord (it goes on looking past one, to name them all).
         */
        boolean craft(RecipeHolder<CraftingRecipe> r, int depth, Set<Item> making, List<Step> steps, boolean report) {
            CraftingRecipe rec = r.value();
            List<Ingredient> ings = rec.getIngredients();
            boolean shaped = rec instanceof ShapedRecipe;
            int w, h;
            if (shaped) {
                w = ((ShapedRecipe) rec).getWidth();
                h = ((ShapedRecipe) rec).getHeight();
            } else {
                int n = 0;
                for (Ingredient i : ings) {
                    n += i.isEmpty() ? 0 : 1;
                }
                if (n > 9) {
                    return false;
                }
                w = h = n <= 4 ? 2 : 3;
            }
            List<ItemStack> grid = new ArrayList<>(Collections.nCopies(w * h, ItemStack.EMPTY));
            boolean ok = true;
            int slot = 0;
            for (int i = 0; i < ings.size(); i++) {
                Ingredient ing = ings.get(i);
                if (ing.isEmpty()) {
                    continue;
                }
                ItemStack got = source(ing, depth, making, steps);
                if (got.isEmpty()) {
                    ok = false;
                    if (!report) {
                        return false;
                    }
                    missing.merge(name(ing), 1, Integer::sum);
                    continue;
                }
                int at = shaped ? i : slot++;
                if (at >= grid.size()) {
                    return false;
                }
                grid.set(at, got);
            }
            if (!ok) {
                return false;
            }
            steps.add(new Step(r, grid, w, h));
            ItemStack out = rec.getResultItem(level.registryAccess());
            stock.merge(Key.of(out), out.getCount(), Integer::sum);
            return true;
        }

        /** One of what {@code ing} takes, out of the stock (the most plentiful kind) or made on the way; empty if neither. */
        ItemStack source(Ingredient ing, int depth, Set<Item> making, List<Step> steps) {
            Key best = null;
            int most = 0;
            for (var e : stock.entrySet()) {
                if (e.getValue() > most && usable(e.getKey().stack()) && ing.test(e.getKey().stack())) {
                    best = e.getKey();
                    most = e.getValue();
                }
            }
            if (best != null) {
                take(best);
                return best.stack().copy();
            }
            if (depth >= DEPTH) {
                return ItemStack.EMPTY;
            }
            Set<Item> tried = new HashSet<>();
            for (ItemStack opt : ing.getItems()) {
                Item it = opt.getItem();
                if (!tried.add(it) || making.contains(it)) {
                    continue;
                }
                for (RecipeHolder<CraftingRecipe> sub : recipesFor(level, it)) {
                    if (--budget <= 0) {
                        return ItemStack.EMPTY;
                    }
                    ItemStack made = sub.value().getResultItem(level.registryAccess());
                    if (!ing.test(made)) {
                        continue;
                    }
                    Map<Key, Integer> before = new HashMap<>(stock);
                    int stepsBefore = steps.size();
                    making.add(it);
                    boolean done = craft(sub, depth + 1, making, steps, false);
                    making.remove(it);
                    Key k = Key.of(made);
                    if (done && stock.getOrDefault(k, 0) > 0) {
                        take(k);
                        return k.stack().copy();
                    }
                    stock.clear();
                    stock.putAll(before);
                    while (steps.size() > stepsBefore) {
                        steps.remove(steps.size() - 1);
                    }
                }
            }
            return ItemStack.EMPTY;
        }

        void take(Key k) {
            int n = stock.getOrDefault(k, 0) - 1;
            if (n <= 0) {
                stock.remove(k);
            } else {
                stock.put(k, n);
            }
        }
    }

    /** A making, as the Warlord would name what the Hoard lacks: "Iron Ingot", "any Planks" for a tag of many kinds. */
    static String name(Ingredient ing) {
        ItemStack[] opts = ing.getItems();
        if (opts.length == 0) {
            return "something";
        }
        Set<Item> kinds = new HashSet<>();
        for (ItemStack o : opts) {
            kinds.add(o.getItem());
        }
        String first = opts[0].getHoverName().getString();
        if (kinds.size() <= 1) {
            return first;
        }
        // "Oak Planks", "Birch Planks"... → "any Planks"; else the first, "or like"
        String[] words = first.split(" ");
        String last = words[words.length - 1];
        boolean shared = true;
        for (ItemStack o : opts) {
            if (!o.getHoverName().getString().endsWith(last)) {
                shared = false;
                break;
            }
        }
        return shared ? "any " + last : first + " (or like)";
    }

    // ------------------------------------------------------------------ the making

    /**
     * Make it: every step's makings taken (what earlier steps made first, then the Hoard's barrels, then the Heart's
     * warpstone), each craft done, the makings' roads logged ({@code by} is the place: the Warlock's Workshop). Returns
     * everything the plan left in hand (the thing made first among them, then the leftovers), or null if a making was
     * gone (everything taken goes back).
     */
    @Nullable
    public static List<ItemStack> make(ServerLevel level, Settlement heart, List<BlockPos> hoard, Plan plan, String by) {
        List<ItemStack> pool = new ArrayList<>();
        Map<Item, Integer> fromHoard = new HashMap<>();
        int shards = 0;
        ItemStack last = ItemStack.EMPTY;
        for (Step s : plan.steps()) {
            List<ItemStack> grid = new ArrayList<>(s.grid().size());
            boolean ok = true;
            for (ItemStack want : s.grid()) {
                if (want.isEmpty()) {
                    grid.add(ItemStack.EMPTY);
                    continue;
                }
                ItemStack got = fromPool(pool, want);
                if (got.isEmpty()) {
                    got = Bins.take(level, hoard, x -> ItemStack.isSameItemSameComponents(x, want) && usable(x), 1);
                    if (!got.isEmpty()) {
                        fromHoard.merge(got.getItem(), 1, Integer::sum);
                    }
                }
                if (got.isEmpty() && want.is(heart.currencyItem()) && heart.currency() > 0 && heart.spendCurrency(1, by)) {
                    got = new ItemStack(heart.currencyItem());
                    shards++;
                }
                if (got.isEmpty()) {
                    ok = false;
                    break;
                }
                grid.add(got);
            }
            CraftingInput input = ok && grid.size() == s.width() * s.height() ? CraftingInput.of(s.width(), s.height(), grid) : null;
            if (input == null || !s.recipe().value().matches(input, level)) {
                pool.addAll(grid);                                      // back they all go
                giveBack(level, heart, hoard, pool, fromHoard, shards, by);
                return null;
            }
            ItemStack out = s.recipe().value().assemble(input, level.registryAccess());
            NonNullList<ItemStack> rest = s.recipe().value().getRemainingItems(input);
            for (ItemStack r : rest) {
                if (!r.isEmpty()) {
                    pool.add(r.copy());
                }
            }
            if (!out.isEmpty()) {
                pool.add(out.copy());
                last = out;
            }
        }
        for (var e : fromHoard.entrySet()) {
            heart.log(Works.HOARD, by, e.getKey(), e.getValue());
        }
        // the thing made first: the rest are leftovers
        List<ItemStack> hand = new ArrayList<>();
        for (int i = pool.size() - 1; i >= 0; i--) {
            if (!last.isEmpty() && ItemStack.isSameItemSameComponents(pool.get(i), last)) {
                hand.add(pool.remove(i));
            }
        }
        hand.addAll(pool);
        return merge(hand);
    }

    /** One of {@code want} out of what earlier steps made, or empty. */
    private static ItemStack fromPool(List<ItemStack> pool, ItemStack want) {
        for (ItemStack p : pool) {
            if (!p.isEmpty() && ItemStack.isSameItemSameComponents(p, want)) {
                return p.split(1);
            }
        }
        return ItemStack.EMPTY;
    }

    /** A making gone half-way: everything taken back where it came from (the Hoard, the Heart's warpstone). */
    private static void giveBack(ServerLevel level, Settlement heart, List<BlockPos> hoard, List<ItemStack> pool,
                                 Map<Item, Integer> fromHoard, int shards, String by) {
        int owed = shards;                                              // the Heart's shards go back into the Heart
        for (ItemStack s : pool) {
            if (s.isEmpty()) {
                continue;
            }
            if (owed > 0 && s.is(heart.currencyItem())) {
                int n = Math.min(owed, s.getCount());
                owed -= n;
                s = s.copyWithCount(s.getCount() - n);
                if (s.isEmpty()) {
                    continue;
                }
            }
            ItemStack rest = Bins.insert(level, hoard, s);
            if (!rest.isEmpty()) {
                heart.addSpoil(rest);
            }
        }
        if (shards > 0) {
            heart.addCurrency(shards, by);
        }
        // what was taken and went back is no road: nothing is logged
        fromHoard.clear();
    }

    /** Like stacks put together. */
    private static List<ItemStack> merge(List<ItemStack> in) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : in) {
            if (s.isEmpty()) {
                continue;
            }
            boolean added = false;
            for (ItemStack o : out) {
                if (ItemStack.isSameItemSameComponents(o, s) && o.getCount() + s.getCount() <= o.getMaxStackSize()) {
                    o.grow(s.getCount());
                    added = true;
                    break;
                }
            }
            if (!added) {
                out.add(s.copy());
            }
        }
        return out;
    }
}
