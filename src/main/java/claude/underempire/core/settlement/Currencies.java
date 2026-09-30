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
 */
public final class Currencies {

    private record Currency(Supplier<? extends Item> unit, Supplier<? extends Item> block, @Nullable Supplier<? extends Item> refined,
                            List<Supplier<? extends Block>> ores) {
    }

    private static final List<Currency> ALL = new ArrayList<>();

    private Currencies() {
    }

    @SafeVarargs
    public static synchronized void register(Supplier<? extends Item> unit, Supplier<? extends Item> block,
                                             @Nullable Supplier<? extends Item> refined, Supplier<? extends Block>... ores) {
        ALL.add(new Currency(unit, block, refined, List.of(ores)));
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
}
