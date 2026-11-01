package claude.settlementcore.work;

import net.minecraft.core.HolderLookup;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.UseRemainder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the engine reads off a recipe, the way 26.1 tells it (2026-10-31). Since 1.21.2 a recipe no longer says what it makes
 * without an input (getResultItem) or lists its ingredients (getIngredients): its result is in its displays, which the recipe
 * book draws from, and its makings in its placement info. Every recipe the engine reads (the Workshop's crafts, the kitchen's
 * dishes, the forge's smelts, fare's grades) goes through here.
 */
public final class RecipeView {

    private RecipeView() {
    }

    /** What {@code r} makes, as its first display shows it; empty for a special recipe whose result depends on what goes in. */
    public static ItemStack result(Recipe<?> r, HolderLookup.Provider registries) {
        ContextMap context = new ContextMap.Builder().withParameter(SlotDisplayContext.REGISTRIES, registries).create(SlotDisplayContext.CONTEXT);
        try {
            for (RecipeDisplay d : r.display()) {
                ItemStack s = d.result().resolveForFirstStack(context);
                if (!s.isEmpty()) {
                    return s;
                }
            }
        } catch (RuntimeException e) {
            return ItemStack.EMPTY;                                  // a special recipe with no set result
        }
        return ItemStack.EMPTY;
    }

    /** {@code r}'s makings, the empty slots left out. */
    public static List<Ingredient> ingredients(Recipe<?> r) {
        if (r instanceof ShapedRecipe s) {
            return s.getIngredients().stream().flatMap(Optional::stream).toList();
        }
        return r.placementInfo().ingredients();
    }

    /**
     * A crafting recipe's slots as 1.21.1 listed them: a shaped one's grid row by row, its empty cells empty; any other's
     * makings in order.
     */
    public static List<Optional<Ingredient>> slots(CraftingRecipe r) {
        if (r instanceof ShapedRecipe s) {
            return s.getIngredients();
        }
        List<Optional<Ingredient>> out = new ArrayList<>();
        for (Ingredient i : r.placementInfo().ingredients()) {
            out.add(Optional.of(i));
        }
        return out;
    }

    /** Every kind {@code ing} takes, one of each. */
    public static List<ItemStack> items(Ingredient ing) {
        return ing.items().map(ItemStack::new).toList();
    }

    /** What is left in the grid when {@code s} is crafted with (a bucket from milk), or empty. */
    public static ItemStack remainder(ItemStack s) {
        ItemStackTemplate t = s.getItem().getCraftingRemainder(s);
        return t == null ? ItemStack.EMPTY : t.create();
    }

    /** What is left in the hand once {@code s} is eaten or drunk (a stew's bowl), or empty. */
    public static ItemStack useRemainder(ItemStack s) {
        UseRemainder r = s.get(DataComponents.USE_REMAINDER);
        return r == null ? ItemStack.EMPTY : r.convertInto().create();
    }
}
