package claude.settlementcore.food;

import claude.settlementcore.work.RecipeView;
import claude.settlementcore.Core;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import net.minecraft.util.Unit;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The clan's fare (user, 2026-09-25: "we should do quality of food as a feature"; picked: leadership,
 * breeding and healing, the fare as the larder's average grade, cooking by the Furnace Chambers'
 * smokers and a Cook-Pits room). Like the food stock of Total War's Skaven, a clan fed well grows
 * faster, heals faster and holds longer in a fight, and a clan living on slop does none of it well.
 *
 * <p>Every food that goes into the larder keeps its grade ({@link #grade}): slop (rotten flesh and
 * worse), raw, cooked, feast (stews, pies, golden food, cake), gorge (a dish made of feasts: Pam's
 * HarvestCraft's combo meals and gourmet burgers; or a feast laced with warpstone, below). The larder keeps
 * a pile per grade, the clan eats its worst first, and the fare is the average grade of what is left
 * ({@link #of}). The grades come from item tags (data/settlementcore/tags/item/fare_*.json, built on the
 * common c:foods tags, so other mods' food falls in line). Food no tag names, like most of Pam's
 * HarvestCraft's dishes, gets its grade from how it is made ({@link #derive}): cooked out of a furnace or at
 * a crafting grid, a feast when a dish puts foods together, a gorge when it puts feasts together, raw when
 * no recipe makes it.
 *
 * <p>Gorge (user, 2026-09-25: "because of pams harvest craft, should we add more tiers", then "both": the
 * gorge grade and warp-spicing). Pam's made 656 of its foods feasts, from fries to a Weekend Picnic, so the
 * top grade was split: seasonings no longer count as a food (fries and jerky are cooked), and a dish of
 * feasts is a gorge. Warp-spicing is the vanilla way up: a feast laced with warpstone ({@link #SPICED}, by
 * the cooks or at a crafting grid) is a gorge.
 */
public enum Fare {
    SLOP("Slop", ChatFormatting.DARK_GRAY, -8, 0.7f, 0.5f),
    PLAIN("Plain", ChatFormatting.GRAY, 0, 1.0f, 1.0f),
    GOOD("Good", ChatFormatting.GREEN, 5, 1.25f, 1.5f),
    FEAST("Feast", ChatFormatting.GOLD, 10, 1.5f, 2.0f),
    GORGING("Gorging", ChatFormatting.LIGHT_PURPLE, 15, 1.75f, 2.5f);

    public final String label;
    public final ChatFormatting color;
    /** Leadership added to every rat of the clan in a fight. */
    public final int courage;
    /** Litters and raisings are this many times the size. */
    public final float breeding;
    /** Wounds heal this many times as fast. */
    public final float healing;

    Fare(String label, ChatFormatting color, int courage, float breeding, float healing) {
        this.label = label;
        this.color = color;
        this.courage = courage;
        this.breeding = breeding;
        this.healing = healing;
    }

    public static final int SLOP_GRADE = 0, RAW = 1, COOKED = 2, FEAST_GRADE = 3, GORGE_GRADE = 4;
    public static final String[] GRADES = {"slop", "raw", "cooked", "feast", "gorge"};

    public static final TagKey<Item> TAG_SLOP = tag("fare_slop");
    public static final TagKey<Item> TAG_RAW = tag("fare_raw");
    public static final TagKey<Item> TAG_COOKED = tag("fare_cooked");
    public static final TagKey<Item> TAG_FEAST = tag("fare_feast");
    public static final TagKey<Item> TAG_GORGE = tag("fare_gorge");
    /** Salt, spices, sauces, oil, vinegar: they season a dish, they do not make it a meal ({@link #derive}). */
    public static final TagKey<Item> TAG_SEASONING = tag("fare_seasoning");

    private static TagKey<Item> tag(String id) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Core.NAMESPACE, id));
    }

    /** The fare of a larder whose average grade is {@code avg} (0 slop .. 4 gorge): gorging takes half of it gorge, the rest feasts. */
    public static Fare of(double avg) {
        return avg < 0.75 ? SLOP : avg < 1.5 ? PLAIN : avg < 2.25 ? GOOD : avg < 3.5 ? FEAST : GORGING;
    }

    /** A food's grade (0 slop .. 4 gorge), or -1 for anything the larder won't take. */
    public static int grade(ItemStack s) {
        if (nutrition(s) <= 0) {
            return -1;
        }
        int t = tagged(s);
        int g = t >= 0 ? t : derived(s);
        if (g == RAW && rich(s)) {
            return COOKED;                                       // rich enough raw (Under-Empire's warp-meat)
        }
        return g == FEAST_GRADE && spiced(s) ? GORGE_GRADE : g;
    }

    /** Marks that make raw food rich enough to count as cooked (Under-Empire's warp-meat), registered as a race starts. */
    private static final List<Supplier<? extends DataComponentType<?>>> RICH = new CopyOnWriteArrayList<>();
    /** The mark that seasons a feast into a gorge (Under-Empire's warp-spicing), or null: nothing seasons. */
    @Nullable
    private static volatile Supplier<? extends DataComponentType<Unit>> seasoning;

    public static void richWhenRaw(Supplier<? extends DataComponentType<?>> mark) {
        RICH.add(mark);
    }

    public static void seasonedBy(Supplier<? extends DataComponentType<Unit>> mark) {
        seasoning = mark;
    }

    private static boolean rich(ItemStack s) {
        for (var m : RICH) {
            if (s.has(m.get())) {
                return true;
            }
        }
        return false;
    }

    /** Currency to season a feast with: one unit seasons this many dishes, the cooks' batch. */
    public static final int SPICED_PER_SHARD = 4;

    /** Whether a food is laced with warpstone: a spiced feast is a gorge. */
    public static boolean spiced(ItemStack s) {
        var m = seasoning;
        return m != null && s.has(m.get());
    }

    /** Whether warpstone would lift this food: an unspiced feast. */
    public static boolean spiceable(ItemStack s) {
        return !s.isEmpty() && !spiced(s) && grade(s) == FEAST_GRADE;
    }

    /** {@code s} laced with warpstone. */
    public static ItemStack spice(ItemStack s) {
        var m = seasoning;
        if (m != null) {
            s.set(m.get(), Unit.INSTANCE);
        }
        return s;
    }

    private static Map<Item, Integer> derived = Map.of();
    private static RecipeManager derivedFrom;

    /** The grade a food no fare tag names gets from how it is made, worked out again after each datapack reload. */
    private static synchronized int derived(ItemStack s) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return RAW;
        }
        RecipeManager rm = server.getRecipeManager();
        if (rm != derivedFrom) {
            derived = derive(rm.getRecipes(), server.registryAccess());
            derivedFrom = rm;
        }
        return derived.getOrDefault(s.getItem(), RAW);
    }

    /** A dish that puts foods together is a feast when it fills at least this much (a vanilla stew); less is a snack. */
    public static final int MEAL = 6;

    /** A food's grade by its fare tags alone, or -1 if none names it. */
    public static int tagged(ItemStack s) {
        return s.is(TAG_SLOP) ? SLOP_GRADE : s.is(TAG_GORGE) ? GORGE_GRADE : s.is(TAG_FEAST) ? FEAST_GRADE : s.is(TAG_COOKED) ? COOKED
                : s.is(TAG_RAW) ? RAW : -1;
    }

    /**
     * Each food's grade by how it is made (for the food no fare tag names; the tags come first):
     * <ul>
     * <li>out of a furnace, smoker or campfire: cooked;</li>
     * <li>cooked up at a crafting grid: a feast if it puts two or more foods together into a meal (a burger, a
     * pizza, a stew of meat and roots: {@link #MEAL} or more) or comes in a bowl (a soup or stew), otherwise
     * cooked (buttered toast, a juice, butter). Seasonings ({@link #TAG_SEASONING}: salt, spices, sauces,
     * oil) are not a food of it: fries and jerky are cooked potato and meat;</li>
     * <li>a feast that puts two or more feasts together (a Cookout Meal of a burger and a hot dog, a chili dog, a
     * gourmet burger): a gorge;</li>
     * <li>cut from a bigger food (a pizza into slices): whatever that food is;</li>
     * <li>made by no recipe (a crop, a catch, a drop): not in the map, so raw.</li>
     * </ul>
     * Opening a crate or a block back into what was packed in it is not cooking. Where several recipes make a
     * food, the best counts. Grading by how many foods go in, not by how filling the dish is, keeps other mods'
     * scales out of it: Pam's HarvestCraft's apple juice fills 9, more than a vanilla stew.
     */
    public static Map<Item, Integer> derive(java.util.Collection<RecipeHolder<?>> recipes, RegistryAccess access) {
        Map<Item, Integer> m = new HashMap<>();
        List<Item[]> cuts = new java.util.ArrayList<>();            // {the whole food, what it is cut into}
        List<Map.Entry<Item, List<Item>>> meals = new java.util.ArrayList<>();   // a feast by its recipe, and its foods
        for (RecipeHolder<?> h : recipes) {
            Recipe<?> r = h.value();
            ItemStack out;
            try {
                out = RecipeView.result(r, access);
            } catch (RuntimeException e) {
                continue;                                        // a special recipe with no set result
            }
            if (out == null || out.isEmpty() || nutrition(out) <= 0) {
                continue;
            }
            RecipeType<?> t = r.getType();
            if (t == RecipeType.SMELTING || t == RecipeType.SMOKING || t == RecipeType.CAMPFIRE_COOKING) {
                m.merge(out.getItem(), COOKED, Math::max);
                continue;
            }
            if (t != RecipeType.CRAFTING) {
                continue;
            }
            List<Ingredient> used = RecipeView.ingredients(r).stream().filter(i -> !i.isEmpty()).toList();
            List<Item> kinds = used.stream().map(Fare::firstItem).filter(Objects::nonNull).distinct().toList();
            if (kinds.isEmpty()) {
                continue;
            }
            if (kinds.size() == 1 && out.getCount() > 1) {
                if (nutrition(new ItemStack(kinds.get(0))) > 0) {
                    cuts.add(new Item[]{kinds.get(0), out.getItem()});
                }
                continue;                                        // a crate opened, or a food cut up
            }
            List<Item> foods = used.stream().map(Fare::firstFood).filter(Objects::nonNull).distinct().toList();
            boolean meal = foods.size() >= 2 && nutrition(out) >= MEAL;
            m.merge(out.getItem(), meal || leftover(out).is(Items.BOWL) ? FEAST_GRADE : COOKED, Math::max);
            if (meal) {
                meals.add(Map.entry(out.getItem(), foods));
            }
        }
        for (int pass = 0; pass < 2; pass++) {                  // twice, for a slice cut from a cut food
            for (Item[] c : cuts) {
                ItemStack whole = new ItemStack(c[0]);
                m.merge(c[1], tagged(whole) >= 0 ? tagged(whole) : m.getOrDefault(c[0], RAW), Math::max);
            }
        }
        // a gorge: a meal of two or more feasts (by the grades above, so one pass; a gorge made of gorges is one too)
        List<Item> gorges = new java.util.ArrayList<>();
        for (Map.Entry<Item, List<Item>> meal : meals) {
            long feasts = meal.getValue().stream().filter(f -> {
                int t = tagged(new ItemStack(f));
                return (t >= 0 ? t : m.getOrDefault(f, RAW)) >= FEAST_GRADE;
            }).count();
            if (feasts >= 2) {
                gorges.add(meal.getKey());
            }
        }
        gorges.forEach(i -> m.put(i, GORGE_GRADE));
        return m;
    }

    private static Item firstItem(Ingredient i) {
        List<ItemStack> any = RecipeView.items(i);
        return any.isEmpty() ? null : any.get(0).getItem();
    }

    /** The first food an ingredient takes, or null if it takes none (a tool, a bowl, sugar) or only seasons (salt, a sauce). */
    private static Item firstFood(Ingredient i) {
        for (ItemStack any : RecipeView.items(i)) {
            if (nutrition(any) > 0) {
                return any.is(TAG_SEASONING) ? null : any.getItem();
            }
        }
        return null;
    }

    /** Food points one of these is worth to the larder: its nutrition (a whole cake 14, a sheaf of wheat 2). */
    public static int nutrition(ItemStack s) {
        FoodProperties fp = s.get(DataComponents.FOOD);
        if (fp != null) {
            return fp.nutrition();
        }
        if (s.is(Tags.Items.FOODS_EDIBLE_WHEN_PLACED) || s.is(Items.CAKE)) {
            return 14;                                           // seven slices of 2
        }
        return s.is(Items.WHEAT) ? 2 : 0;
    }

    /** What is left in the hand once one is eaten (a stew's bowl), or empty. */
    public static ItemStack leftover(ItemStack s) {
        return RecipeView.useRemainder(s);
    }
}
