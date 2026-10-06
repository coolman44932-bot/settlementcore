package claude.underempire.core.work;

import claude.underempire.core.unit.SettlerMob;
import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.settlement.Building;
import claude.underempire.core.room.PlanPlacement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Cook-Pits (the clan's fare, 2026-09-25: "Smokers + a Cook-Pits room"; the cooks make Pam's HarvestCraft's
 * dishes too, the same day: "Both"). The cooks make the best the Pantry allows, one batch a chore, and put
 * it in the Cooked Fare barrels, from where the horde takes it to the larder (Works#haul). What they can make
 * is every food recipe the server knows (KitchenBook: vanilla's, Warren Stew, Pam's): a feast before cooked
 * fare, the most filling first; when the best they could make lacks some of its makings (flour, dough,
 * fried meat, bowls) they make those first, back into the Pantry. Bowls are carved from the Grove's timber
 * (a log gives four). Last, whatever raw food is left is roasted on the pits. Pam's cooking tools stay in the
 * Pantry; water is drawn from the stew-cauldron. The horde keeps the Pantry stocked from the Fields, the
 * Beast Pens, the Warp-Orchard, the Grove and the Furnace Chambers' smokers.
 *
 * <p>Warp-spicing (2026-09-25, with the gorge grade): every feast the cooks make is laced with warpstone from
 * the Heart's hoard, a shard a batch of four, which makes it a gorge (clan/Fare#spiced); never while the
 * hoard holds {@link #SPICE_RESERVE} or less, which is kept for digging.
 */
public class CookWork extends RoomWork {

    public static final String PANTRY = "Pantry", FARE_BIN = "Cooked Fare";

    /** The warpstone the cooks leave in the hoard for the gnaw-plans: they spice with what is above it. */
    public static final int SPICE_RESERVE = 20;

    /**
     * A dish: what goes in (each a pick and how many), how to make it, where it is made, how many at once;
     * whether what it makes goes back into the Pantry (makings, bowls) rather than out to the larder; the
     * tools it needs that stay in the Pantry.
     */
    record Dish(String what, String at, int batch, List<Need> needs, BiFunction<ServerLevel, List<ItemStack>, ItemStack> make,
                boolean intoPantry, List<Item> keep) {
    }

    record Need(Predicate<ItemStack> pick, int count) {
    }

    static boolean cookedMeat(ItemStack s) {
        return s.is(Tags.Items.FOODS_COOKED_MEAT) || s.is(Tags.Items.FOODS_COOKED_FISH);
    }

    /** The older tag Pam's HarvestCraft files its vegetables under (tomatoes, onions, cabbage...), beside c:foods/vegetable. */
    static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> VEGETABLES = net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.ITEM, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("c", "vegetables"));

    static boolean vegetable(ItemStack s) {
        return (s.is(Tags.Items.FOODS_VEGETABLE) || s.is(VEGETABLES) && claude.underempire.core.food.Fare.nutrition(s) > 0) && !s.is(Items.GOLDEN_CARROT);
    }

    /** Bowls, carved from the Grove's timber when a dish wants one: a log gives four. */
    static final Dish BOWLS = new Dish("carving bowls", "bench", 2, List.of(new Need(s -> s.is(ItemTags.PLANKS) || s.is(ItemTags.LOGS), 1)),
            (l, in) -> new ItemStack(Items.BOWL, in.get(0).is(ItemTags.LOGS) ? 4 : 1), true, List.of());

    /** Last of all: raw food roasted on the pits. */
    static final Dish ROAST = new Dish("roasting", "fire", 8, List.of(new Need(CookWork::roastable, 1)), (l, in) -> roasted(l, in.get(0)),
            false, List.of());

    static boolean roastable(ItemStack s) {
        return !s.isEmpty() && s.has(net.minecraft.core.component.DataComponents.FOOD) && !cookedMeat(s)
                && claude.underempire.core.food.Fare.grade(s) <= claude.underempire.core.food.Fare.RAW
                && ROASTS.get() != null && ROASTS.get().test(s);
    }

    /** Set per server on first use: whether a food has a campfire recipe. */
    static final java.util.concurrent.atomic.AtomicReference<Predicate<ItemStack>> ROASTS = new java.util.concurrent.atomic.AtomicReference<>();

    static ItemStack roasted(ServerLevel level, ItemStack raw) {
        return level.getRecipeManager().getRecipeFor(RecipeType.CAMPFIRE_COOKING, new SingleRecipeInput(raw), level)
                .map(r -> r.value().assemble(new SingleRecipeInput(raw), level.registryAccess())).orElse(ItemStack.EMPTY);
    }

    List<BlockPos> pantry = List.of();
    List<BlockPos> out = List.of();
    List<BlockPos> pots = List.of(), fires = List.of(), ovens = List.of(), benches = List.of();
    /** Whether the stew-cauldron holds water to draw (Pam's HarvestCraft's fresh water). */
    boolean water;
    long cooked;
    /** Dishes laced with warpstone, and whether the last feast went unspiced for want of warpstone above the reserve. */
    long spiced;
    private boolean spiceWanted;
    /** What the cooks have made for the larder (the last 64 kinds), and the last of it: the Heart's report shows it. */
    public final java.util.Set<Item> madeKinds = new java.util.LinkedHashSet<>();
    @Nullable private String last;
    @Nullable private Dish planned;
    /**
     * What the cooks carry from the Pantry to the pits right now, until when: planned with as if still in the
     * Pantry, or the other cook, seeing the wheat gone to the oven, gives the burger up and spends the egg.
     */
    private final List<Object[]> inHand = new ArrayList<>();

    /** The dish whose makings the cooks are making: they finish it before they start on another. */
    @Nullable private net.minecraft.resources.ResourceLocation working;
    private long plannedAt = Long.MIN_VALUE / 2;

    public CookWork(Settlement heart, Building building, PlanPlacement placement) {
        super(heart, building, placement);
    }

    @Override
    protected void scan(ServerLevel level) {
        pantry = Bins.named(level, box(), PANTRY);
        out = Bins.named(level, box(), FARE_BIN);
        List<BlockPos> p = new ArrayList<>(), f = new ArrayList<>(), o = new ArrayList<>(), b = new ArrayList<>();
        boolean w = false;
        var box = box();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    if (s.is(Blocks.WATER_CAULDRON) || s.is(Blocks.CAULDRON)) {
                        p.add(m.immutable());
                        w |= s.is(Blocks.WATER_CAULDRON);
                    } else if (s.is(Blocks.CAMPFIRE)) {
                        f.add(m.immutable());
                    } else if (s.is(Blocks.SMOKER) || s.is(Blocks.FURNACE)) {
                        o.add(m.immutable());
                    } else if (s.is(Blocks.CRAFTING_TABLE)) {
                        b.add(m.immutable());
                    }
                }
            }
        }
        pots = p;
        fires = f;
        ovens = o;
        benches = b;
        water = w;
        if (ROASTS.get() == null) {
            ROASTS.set(s -> level.getRecipeManager().getRecipeFor(RecipeType.CAMPFIRE_COOKING, new SingleRecipeInput(s), level).isPresent());
        }
    }

    /** How many dishes the cooks have laced with warpstone. */
    public long spiced() {
        return spiced;
    }

    public List<BlockPos> pantry(ServerLevel level) {
        rescan(level);
        return pantry;
    }

    public List<BlockPos> fareBins(ServerLevel level) {
        rescan(level);
        return out;
    }

    /** Whether the Pantry should be stocked with this: what the kitchen's recipes use (KitchenBook). */
    public boolean wants(ServerLevel level, ItemStack s) {
        return KitchenBook.of(level).wants(s);
    }

    /** Why the cooks do or do not make {@code dish} now (KitchenBook#why): for the tests. */
    public String why(ServerLevel level, Item dish) {
        rescan(level);
        return KitchenBook.of(level).why(KitchenBook.contents(level, pantry), water, canMake(level, BOWLS) > 0, dish);
    }

    /** The time a plan may take (KitchenBook): on the clock, and in steps on the GameTest server. For the tests. */
    public static final long PLAN_NANOS = KitchenBook.PLAN_NANOS, PLAN_STEPS = KitchenBook.PLAN_STEPS;

    /**
     * What a plan costs now (KitchenBook#plan, as the cooks would make it): {steps, of them makings tried,
     * nanoseconds}. A step is a recipe matched against the Pantry. For the tests, which hold a plan to its steps:
     * on the GameTest server's clock the time is whatever else the PC is doing.
     */
    public long[] planCost(ServerLevel level) {
        rescan(level);
        KitchenBook book = KitchenBook.of(level);
        Map<Item, Integer> have = KitchenBook.contents(level, pantry);
        boolean canCarve = canMake(level, BOWLS) > 0;
        long steps = book.steps, tries = book.tries, t = System.nanoTime();
        book.plan(have, water, canCarve, working);
        return new long[]{book.steps - steps, book.tries - tries, System.nanoTime() - t};
    }

    @Override
    public int crewWanted() {
        return 2;
    }

    @Override
    public void second(ServerLevel level, int crewHere) {
        rescan(level);
    }

    /** How many of this dish the Pantry has everything for (up to its batch): its tools there, water to draw. */
    int canMake(ServerLevel level, Dish d) {
        int n = d.batch();
        for (Need need : d.needs()) {
            n = Math.min(n, Bins.count(level, pantry, need.pick()) / need.count());
        }
        for (Item tool : d.keep()) {
            if (Bins.count(level, pantry, s -> s.is(tool)) <= 0) {
                return 0;
            }
        }
        return n;
    }

    /** The best thing to make now (KitchenBook#plan), bowls if that is all the best dish lacks, else roasting. Re-planned every second. */
    @Nullable
    Dish next(ServerLevel level) {
        long now = level.getGameTime();
        if (now - plannedAt < 20 && now >= plannedAt) {
            return planned;
        }
        plannedAt = now;
        planned = plan(level);
        return planned;
    }

    @Nullable
    private Dish plan(ServerLevel level) {
        if (pantry.isEmpty()) {
            return null;
        }
        KitchenBook book = KitchenBook.of(level);
        boolean canCarve = canMake(level, BOWLS) > 0;
        Map<Item, Integer> have = KitchenBook.contents(level, pantry);
        long now = level.getGameTime();
        inHand.removeIf(e -> (long) e[2] < now);
        for (Object[] e : inHand) {
            have.merge((Item) e[0], (Integer) e[1], Integer::sum);
        }
        KitchenBook.Plan p = book.plan(have, water, canCarve, working);
        working = p == null || p.dish() || p.forDish() == null ? null : p.forDish().id();
        if (p != null) {
            if (p.carving()) {
                return BOWLS;
            }
            Map<Item, Integer> use = new LinkedHashMap<>();
            for (Item i : p.use()) {
                use.merge(i, 1, Integer::sum);
            }
            List<Need> needs = new ArrayList<>();
            use.forEach((i, n) -> needs.add(new Need(s -> s.is(i), n)));
            ItemStack result = p.step().result();
            String what = p.dish() ? p.step().what() : p.step().what() + " for the " + p.forDish().result().getHoverName().getString();
            return new Dish(what, p.step().at(), p.dish() ? 4 : 1, needs, (l, in) -> result.copy(), !p.dish(), p.keep());
        }
        return canMake(level, ROAST) > 0 ? ROAST : null;
    }

    private BlockPos station(Dish d, BlockPos from) {
        List<BlockPos> at = switch (d.at()) {
            case "pot" -> pots;
            case "oven" -> ovens;
            case "bench" -> benches;
            default -> fires;
        };
        if (at.isEmpty()) {
            at = !fires.isEmpty() ? fires : !pots.isEmpty() ? pots : List.of(box().getCenter());
        }
        BlockPos best = nearest(at, from);
        return best == null ? box().getCenter() : best;
    }

    @Override
    @Nullable
    public Chore nextChore(ServerLevel level, SettlerMob rat) {
        rescan(level);
        long now = level.getGameTime();
        Dish d = next(level);
        if (d == null || pantry.isEmpty() || !free(d.what(), now)) {
            return null;
        }
        BlockPos from = nearest(pantry, rat.blockPosition());
        BlockPos at = station(d, from);
        Need first = d.needs().isEmpty() ? null : d.needs().get(0);
        return new Chore("fetching for the pot", from, 2.6, 6, d.what(), (l, r) -> {
            int batch = canMake(l, d);
            if (batch <= 0) {
                return null;
            }
            Object[] carrying = null;
            if (first != null) {
                ItemStack got = Bins.take(l, pantry, first.pick(), first.count() * batch);   // the first of it, in hand
                r.carry(got);
                if (!got.isEmpty()) {
                    carrying = new Object[]{got.getItem(), got.getCount(), l.getGameTime() + 400};
                    inHand.add(carrying);
                }
            }
            Object[] mine = carrying;
            return new Chore(d.what(), at, 2.4, Math.max(20, Math.round(40 / r.tempo())), d.what(), (l2, r2) -> {
                inHand.remove(mine);
                ItemStack held = r2.carried();
                r2.carry(ItemStack.EMPTY);
                cook(l2, d, held, at);
                return null;
            }).returning(from);
        });
    }

    /**
     * Make as many of {@code d} as the ingredients allow: {@code held} is the first ingredient, already
     * taken; the rest come out of the Pantry now. What a thing leaves in the grid (a bucket, a Pam's pot)
     * goes back in, and so does whatever is left over.
     */
    int cook(ServerLevel level, Dish d, ItemStack held, BlockPos at) {
        planned = null;                                          // what is in the Pantry changes: plan again
        plannedAt = Long.MIN_VALUE / 2;
        boolean hasFirst = !d.needs().isEmpty();
        int batch = d.batch();
        if (hasFirst) {
            batch = Math.min(batch, held.getCount() / d.needs().get(0).count());
        }
        for (int i = hasFirst ? 1 : 0; i < d.needs().size(); i++) {
            Need need = d.needs().get(i);
            batch = Math.min(batch, Bins.count(level, pantry, need.pick()) / need.count());
        }
        for (Item tool : d.keep()) {
            if (Bins.count(level, pantry, s -> s.is(tool)) <= 0) {
                batch = 0;
            }
        }
        int made = 0, laceLeft = 0;
        for (int b = 0; b < batch; b++) {
            List<ItemStack> in = new ArrayList<>();
            if (hasFirst) {
                in.add(held.split(d.needs().get(0).count()));
            }
            for (int i = hasFirst ? 1 : 0; i < d.needs().size(); i++) {
                Need need = d.needs().get(i);
                in.add(Bins.take(level, pantry, need.pick(), need.count()));
            }
            ItemStack dish = d.make().apply(level, in);
            if (dish.isEmpty()) {
                continue;
            }
            for (ItemStack used : in) {                          // a milk bucket's bucket, back to the Pantry
                ItemStack rem = used.isEmpty() ? ItemStack.EMPTY : used.getCraftingRemainingItem();
                if (!rem.isEmpty()) {
                    rem.setCount(used.getCount());
                    ItemStack rest = Bins.insert(level, pantry, rem);
                    if (!rest.isEmpty()) {
                        Block.popResource(level, at.above(), rest);
                    }
                }
            }
            if (!d.intoPantry() && claude.underempire.core.food.Fare.spiceable(dish)) {
                if (laceLeft <= 0 && heart.currency() > SPICE_RESERVE && heart.spendCurrency(1, "Spicing feasts")) {
                    laceLeft = claude.underempire.core.food.Fare.SPICED_PER_SHARD;   // a pinch of warpstone for the batch
                    level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(0.35f, 0.95f, 0.25f), 1f),
                            at.getX() + 0.5, at.getY() + 1.1, at.getZ() + 0.5, 12, 0.25, 0.2, 0.25, 0.02);
                    level.playSound(null, at, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.7f, 0.6f);
                }
                spiceWanted = laceLeft <= 0;
                if (laceLeft > 0) {
                    laceLeft--;
                    claude.underempire.core.food.Fare.spice(dish);
                    spiced += dish.getCount();
                }
            }
            if (!d.intoPantry()) {
                madeKinds.remove(dish.getItem());
                madeKinds.add(dish.getItem());
                if (madeKinds.size() > 64) {
                    madeKinds.remove(madeKinds.iterator().next());
                }
                last = (claude.underempire.core.food.Fare.spiced(dish) ? "warp-spiced " : "") + dish.getHoverName().getString();
            }
            ItemStack rest = Bins.insert(level, d.intoPantry() ? pantry : out, dish);
            if (!rest.isEmpty()) {
                Block.popResource(level, at.above(), rest);
            }
            made += dish.getCount();
        }
        if (!held.isEmpty()) {
            ItemStack rest = Bins.insert(level, pantry, held);
            if (!rest.isEmpty()) {
                Block.popResource(level, at.above(), rest);
            }
        }
        if (made > 0) {
            if (!d.intoPantry()) {
                cooked += made;
            }
            level.playSound(null, at, d.at().equals("pot") ? SoundEvents.BREWING_STAND_BREW : SoundEvents.CAMPFIRE_CRACKLE,
                    SoundSource.BLOCKS, 0.8f, 0.9f + level.random.nextFloat() * 0.2f);
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.CAMPFIRE_COSY_SMOKE, at.getX() + 0.5, at.getY() + 1.2,
                    at.getZ() + 0.5, 2, 0.2, 0.1, 0.2, 0.01);
        }
        return made;
    }

    /** The night's cooking: a batch every few seconds, as long as the Pantry holds out. */
    @Override
    public void nightsWork(ServerLevel level, int seconds, List<Component> report) {
        rescan(level);
        long before = cooked;
        for (int k = 0; k < seconds / 4; k++) {
            planned = null;
            plannedAt = Long.MIN_VALUE / 2;
            Dish d = next(level);
            if (d == null) {
                break;
            }
            int batch = canMake(level, d);
            ItemStack held = ItemStack.EMPTY;
            if (!d.needs().isEmpty()) {
                Need first = d.needs().get(0);
                held = Bins.take(level, pantry, first.pick(), first.count() * batch);
                if (held.isEmpty()) {
                    break;
                }
            }
            if (batch <= 0 || cook(level, d, held, station(d, box().getCenter())) <= 0) {
                break;
            }
        }
        if (cooked > before) {
            report.add(Component.literal("The cooks made " + (cooked - before) + " dishes.").withStyle(ChatFormatting.DARK_GREEN));
        }
    }

    /** The cooks' makings, in the Pantry: whatever the kitchen's recipes use, no better than cooked fare. */
    @Override
    public List<Supply> supplies(ServerLevel level) {
        rescan(level);
        return List.of(new Supply("the cooks' makings", pantry, s -> wants(level, s), 16));
    }

    @Override
    public void wants(ServerLevel level, List<String> needs, List<String> boosts) {
        rescan(level);
        if (Bins.count(level, pantry, s -> true) == 0) {
            needs.add("food in the Pantry");
        } else if (next(level) == null) {
            needs.add("the makings of a dish (JEI: R on a dish)");
        }
        if (!water) {
            needs.add("water in the stew-cauldron");
        }
        if (spiceWanted) {
            boosts.add(heart.currencyHeld() + " past " + SPICE_RESERVE + ": spiced feasts");
        }
        roomIn(level, out, FARE_BIN, needs);
    }

    @Override
    public void describe(ServerLevel level, List<Component> lines) {
        rescan(level);
        int inPantry = Bins.count(level, pantry, s -> true);
        Dish d = next(level);
        lines.add(Component.literal(room().displayName() + ": " + inPantry + " things in the Pantry" + (d == null ? ", nothing to cook" : ", next: " + d.what())
                + (water ? "" : ", no water in the cauldron") + "; " + cooked + " dishes made" + (spiced > 0 ? ", " + spiced + " warp-spiced" : "")
                + (last == null ? "" : ", the last " + last)
                + (spiceWanted ? "; no " + heart.currencyName() + " to spice the feasts (it keeps " + SPICE_RESERVE + ")" : ""))
                .withStyle(ChatFormatting.DARK_GREEN));
    }

    @Override
    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("cooked", cooked);
        t.putLong("spiced", spiced);
        return t;
    }

    @Override
    public void load(CompoundTag t) {
        cooked = t.getLong("cooked");
        spiced = t.getLong("spiced");
    }
}
