package claude.underempire.core.settlement;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Every race's currency, for the engine's checks that have no settlement at hand (what a hoard's section a thing goes in,
 * how precious it is to smelt): its unit, its block, its refined form and the ores it is mined from. Registered at start-up
 * (Under-Empire: warpstone shards, blocks, refined warpstone and warpstone ore); resolved lazily, so a race registers its
 * items before the registries fill.
 *
 * <p>What a thing is worth in it is {@link #worth}: the unit is one, the block nine, and a race may count more things in
 * ({@link #alsoWorth}: gold nuggets, nine to the ingot).
 */
public final class Currencies {

    private record Currency(Supplier<? extends Item> unit, Supplier<? extends Item> block, @Nullable Supplier<? extends Item> refined,
                            List<Supplier<? extends Block>> ores) {
    }

    /**
     * What an item is worth in the currency it is counted in: {@code per} of it come to {@code units}. A shard is (1, 1), a block
     * of them (9, 1), a gold nugget beside gold ingots (1, 9). Only whole units are counted: nine nuggets are an ingot, and
     * eight are nothing yet.
     */
    public record Worth(int units, int per) {
        public Worth {
            if (units < 1 || per < 1) {
                throw new IllegalArgumentException("a worth of " + units + " per " + per + ": both must be at least 1");
            }
        }

        /** The whole units {@code count} of the item come to. */
        public int unitsOf(int count) {
            return count / per * units;
        }

        /** How many of {@code count} are taken in to pay {@link #unitsOf}: the rest (a part of a unit) stays with whoever holds it. */
        public int itemsFor(int count) {
            return count / per * per;
        }
    }

    private record Also(Supplier<? extends Item> item, Worth worth) {
    }

    private static final Worth UNIT = new Worth(1, 1);
    private static final Worth BLOCK = new Worth(9, 1);

    private static final List<Currency> ALL = new ArrayList<>();
    private static final List<Also> ALSO = new ArrayList<>();

    private Currencies() {
    }

    @SafeVarargs
    public static synchronized void register(Supplier<? extends Item> unit, Supplier<? extends Item> block,
                                             @Nullable Supplier<? extends Item> refined, Supplier<? extends Block>... ores) {
        ALL.add(new Currency(unit, block, refined, List.of(ores)));
    }

    /**
     * Another thing counted in a currency: {@code per} of {@code item} are worth {@code units} (a race with gold ingots for its unit
     * counts nuggets as 1 per 9). The unit and the block are worth what they always were whatever is said here.
     */
    public static synchronized void alsoWorth(Supplier<? extends Item> item, int units, int per) {
        ALSO.add(new Also(item, new Worth(units, per)));
    }

    /**
     * What {@code s} is worth in some race's currency, or null if it is none of it: the unit (1 per 1), the block (9 per 1), then
     * what a race counted in with {@link #alsoWorth}.
     */
    @Nullable
    public static Worth worth(ItemStack s) {
        if (unit(s)) {
            return UNIT;
        }
        if (block(s)) {
            return BLOCK;
        }
        for (Also a : ALSO) {
            if (s.is(a.item().get())) {
                return a.worth();
            }
        }
        return null;
    }

    /** One unit of some race's currency (a warpstone shard). */
    public static boolean unit(ItemStack s) {
        for (Currency c : ALL) {
            if (s.is(c.unit().get())) {
                return true;
            }
        }
        return false;
    }

    /** Some race's currency block. */
    public static boolean block(ItemStack s) {
        for (Currency c : ALL) {
            if (s.is(c.block().get())) {
                return true;
            }
        }
        return false;
    }

    /** Some race's refined currency. */
    public static boolean refined(ItemStack s) {
        for (Currency c : ALL) {
            if (c.refined() != null && s.is(c.refined().get())) {
                return true;
            }
        }
        return false;
    }

    /** An ore some race's currency is mined from. */
    public static boolean ore(BlockState s) {
        for (Currency c : ALL) {
            for (Supplier<? extends Block> b : c.ores()) {
                if (s.is(b.get())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Where the registrations stand now, for {@link #rollBack}. */
    public record Mark(int currencies, int also) {
    }

    /** The engine's own tests: where the registrations stand, before a test registers a currency of its own. */
    public static synchronized Mark mark() {
        return new Mark(ALL.size(), ALSO.size());
    }

    /** The engine's own tests: every currency and worth registered since {@code to} is forgotten, so a test's currency does not outlive it. */
    public static synchronized void rollBack(Mark to) {
        ALL.subList(Math.min(to.currencies(), ALL.size()), ALL.size()).clear();
        ALSO.subList(Math.min(to.also(), ALSO.size()), ALSO.size()).clear();
    }
}
