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
 * Every race's currency: its unit, its block, its refined form and the ores it is mined from. Registered at start-up
 * (Under-Empire: warpstone shards, blocks, refined warpstone and warpstone ore); resolved lazily, so a race registers its
 * items before the registries fill.
 *
 * <p>Every question the engine asks of a currency is asked of a settlement, and answers for that settlement's own currency only
 * (its {@link Settlement#currencyItem} is the key): two races' currencies can be registered at once, and shards put into a gold
 * settlement's ledger are not gold, gold ore cut by a Skaven mine is not warpstone, and a gold block in a Skaven forge's bin is
 * not refined warpstone on its way. What a thing is worth in a settlement's currency is {@link #worth}: its unit is one, its block
 * nine, and a race may count more things in ({@link #alsoWorth}: gold nuggets, nine to the ingot). {@link #unit}, {@link #block},
 * {@link #refined} and {@link #ore} say whether a thing is that settlement's own unit, block, refined form or ore.
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

    private record Also(Supplier<? extends Item> unit, Supplier<? extends Item> item, Worth worth) {
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
     * Another thing counted in the currency whose unit is {@code unit}: {@code per} of {@code item} are worth {@code units} (a race
     * with gold ingots for its unit counts nuggets as 1 per 9). Only a settlement whose own currency item is that unit counts it. The
     * unit and the block are worth what they always were whatever is said here.
     */
    public static synchronized void alsoWorth(Supplier<? extends Item> unit, Supplier<? extends Item> item, int units, int per) {
        ALSO.add(new Also(unit, item, new Worth(units, per)));
    }

    /**
     * What {@code s} is worth in {@code settlement}'s own currency, or null if it is none of it: its unit
     * ({@link Settlement#currencyItem}, 1 per 1), its block ({@link Settlement#currencyBlock}, 9 per 1), then what a race counted in
     * beside that unit with {@link #alsoWorth}. Another race's currency is worth nothing here, registered or not.
     */
    @Nullable
    public static Worth worth(Settlement settlement, ItemStack s) {
        Item unit = settlement.currencyItem();
        if (s.is(unit)) {
            return UNIT;
        }
        if (s.is(settlement.currencyBlock())) {
            return BLOCK;
        }
        for (Also a : ALSO) {
            if (a.unit().get() == unit && s.is(a.item().get())) {
                return a.worth();
            }
        }
        return null;
    }

    /** One unit of {@code settlement}'s own currency (a warpstone shard for the Skaven), and of no other's. */
    public static boolean unit(Settlement settlement, ItemStack s) {
        return s.is(settlement.currencyItem());
    }

    /** {@code settlement}'s own currency block, and no other's. */
    public static boolean block(Settlement settlement, ItemStack s) {
        return s.is(settlement.currencyBlock());
    }

    /** {@code settlement}'s own refined currency, and no other's: nothing for a settlement that refines none. */
    public static boolean refined(Settlement settlement, ItemStack s) {
        Item refined = settlement.refinedCurrency();
        return refined != null && s.is(refined);
    }

    /**
     * An ore {@code settlement}'s own currency is mined from: one registered beside its {@link Settlement#currencyItem unit}, and no
     * other race's (a Skaven mine cutting gold ore finds ore, not warpstone, whatever the Great Plan registered).
     */
    public static boolean ore(Settlement settlement, BlockState s) {
        Item unit = settlement.currencyItem();
        for (Currency c : ALL) {
            if (c.unit().get() != unit) {
                continue;
            }
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
