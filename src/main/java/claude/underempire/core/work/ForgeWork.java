package claude.underempire.core.work;

import claude.underempire.core.settlement.Currencies;
import claude.underempire.core.unit.SettlerMob;
import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.settlement.Building;
import claude.underempire.core.room.PlanPlacement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.Tags;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Furnace Chambers (user, 2026-09-25, picked "slaves run them"): the crew keeps every furnace in the
 * room going from the room's bins. Emptying a furnace's finished work into the Ingot Bin comes first
 * (a full output jams it), then fuel from the Fuel Bin to any furnace with something to cook, then a
 * load (up to 16) from the Ore Bin: ores and raw metal to the blast furnaces, raw food to the
 * smokers, anything else that smelts to the plain furnaces. The slaves at the bellows make every
 * furnace in the room work twice as fast (a second furnace tick each tick, fuel and all) while any of
 * the crew is here. The Warlord can use the furnaces too.
 *
 * <p><b>Refining warpstone</b> (user, 2026-09-26: the Fleshpits ordering their own refined warpstone, "yeah do it"):
 * while the clan wants refined warpstone (the Fleshpits' Feeding Trough for its Rat Ogres, and whatever the Warlord
 * ordered in the Hoard-Ledger) and has less of it than that, in hand or on its way, the chambers press
 * {@link #BLOCK_SHARDS} shards out of the Heart's hoard into a warpstone block and put it in the Ore Bin; the crew
 * blasts it (2 refined a block) like any ore, and the horde carries it on to the trough. Never below
 * {@link #REFINE_RESERVE} in the Heart, which is kept for digging.
 */
public class ForgeWork extends RoomWork {

    public static final String ORE_BIN = "Ore Bin", FUEL_BIN = "Fuel Bin", INGOT_BIN = "Ingot Bin";
    /** A furnace's load: enough for a while, not the whole bin to one furnace. */
    static final int LOAD = 16;
    static final int FUEL_LOAD = 8;

    List<BlockPos> furnaces = List.of();
    List<BlockPos> ores = List.of();
    List<BlockPos> fuel = List.of();
    List<BlockPos> ingots = List.of();
    /** Items smelted since the room was dug (for the report). */
    long smelted;

    /** The warpstone the chambers leave in the Heart's hoard when they refine: they press only what is above it (as the cooks spice). */
    public static final int REFINE_RESERVE = CookWork.SPICE_RESERVE;
    /** Shards pressed into a warpstone block, and the refined warpstone a blast furnace makes of one. */
    public static final int BLOCK_SHARDS = 9, REFINED_PER_BLOCK = 2;
    /** How often the chambers look whether the clan wants refining, in seconds. */
    static final int REFINE_EVERY = 5;
    private int refineClock;
    /** Warpstone blocks pressed since the room was dug. */
    long pressed;
    /** Why refining is held up ("warpstone in the Heart past 29..."), or null. */
    @Nullable public String refineWanting;

    public ForgeWork(Settlement heart, Building building, PlanPlacement placement) {
        super(heart, building, placement);
    }

    @Override
    protected void scan(ServerLevel level) {
        furnaces = Bins.furnaces(level, box());
        ores = Bins.named(level, box(), ORE_BIN);
        fuel = Bins.named(level, box(), FUEL_BIN);
        ingots = Bins.named(level, box(), INGOT_BIN);
    }

    public List<BlockPos> oreBins(ServerLevel level) {
        rescan(level);
        return ores;
    }

    public List<BlockPos> fuelBins(ServerLevel level) {
        rescan(level);
        return fuel;
    }

    public List<BlockPos> ingotBins(ServerLevel level) {
        rescan(level);
        return ingots;
    }

    @Override
    public int crewWanted() {
        return furnaces.size() > 10 ? 3 : 2;
    }

    /**
     * What goes into a furnace of {@code type} holding {@code in} next: more of what it holds, or, empty, the most precious
     * thing in the Ore Bin it can cook (user, 2026-09-26: "can you make it so they smelt high value items like iron first,
     * instead of just copper"). The blast furnaces are loaded first (nextChore), so the best ore goes to the fastest; a plain
     * furnace then takes the next most precious, ore before food before stone. Empty if there is nothing for it.
     */
    public ItemStack loadFor(ServerLevel level, RecipeType<? extends AbstractCookingRecipe> type, ItemStack in) {
        return mostPrecious(level, s -> (in.isEmpty() || ItemStack.isSameItemSameComponents(s, in)) && cooks(level, type, s));
    }

    /** The most precious thing in the Ore Bin that {@code pick} takes (a copy), or empty. */
    private ItemStack mostPrecious(ServerLevel level, java.util.function.Predicate<ItemStack> pick) {
        ItemStack best = ItemStack.EMPTY;
        int bestValue = Integer.MIN_VALUE;
        for (ItemStack s : Bins.stacks(level, ores)) {
            if (s.isEmpty() || !pick.test(s)) {
                continue;
            }
            int v = value(s);
            if (v > bestValue) {
                best = s.copy();
                bestValue = v;
            }
        }
        return best;
    }

    /**
     * How precious a thing is to smelt, the most first: ancient debris, diamond and emerald ore, gold, iron, the other
     * mods' metals (tin, silver, ...), lapis and redstone, copper, coal and quartz ore, then the rest (food for a plain
     * furnace, then stone, sand, logs).
     */
    public static int value(ItemStack s) {
        if (Currencies.block(s)) {
            return 85;                                           // pressed for the Fleshpits (or dropped in by the Warlord): refined at once
        }
        if (s.is(net.minecraft.world.item.Items.ANCIENT_DEBRIS) || s.is(Tags.Items.ORES_NETHERITE_SCRAP)) {
            return 100;
        }
        if (s.is(Tags.Items.ORES_DIAMOND) || s.is(Tags.Items.ORES_EMERALD)) {
            return 90;
        }
        if (s.is(Tags.Items.RAW_MATERIALS_GOLD) || s.is(Tags.Items.ORES_GOLD)) {
            return 80;
        }
        if (s.is(Tags.Items.RAW_MATERIALS_IRON) || s.is(Tags.Items.ORES_IRON)) {
            return 70;
        }
        boolean raw = s.is(Tags.Items.RAW_MATERIALS), ore = s.is(Tags.Items.ORES);
        if (s.is(Tags.Items.RAW_MATERIALS_COPPER) || s.is(Tags.Items.ORES_COPPER)) {
            return 40;
        }
        if (s.is(Tags.Items.ORES_LAPIS) || s.is(Tags.Items.ORES_REDSTONE)) {
            return 50;
        }
        if (s.is(Tags.Items.ORES_COAL) || s.is(Tags.Items.ORES_QUARTZ)) {
            return 30;
        }
        if (raw || ore) {
            return 60;                                           // another mod's metal: tin, silver, lead, ...
        }
        return s.has(net.minecraft.core.component.DataComponents.FOOD) ? 20 : 10;
    }

    public static RecipeType<? extends AbstractCookingRecipe> typeOf(AbstractFurnaceBlockEntity f) {
        return f instanceof BlastFurnaceBlockEntity ? RecipeType.BLASTING : f instanceof SmokerBlockEntity ? RecipeType.SMOKING : RecipeType.SMELTING;
    }

    @SuppressWarnings("unchecked")
    public static Optional<RecipeHolder<AbstractCookingRecipe>> recipe(ServerLevel level, RecipeType<? extends AbstractCookingRecipe> type, ItemStack s) {
        if (s.isEmpty()) {
            return Optional.empty();
        }
        return level.getRecipeManager().getRecipeFor((RecipeType<AbstractCookingRecipe>) type, new SingleRecipeInput(s), level);
    }

    /** Whether any furnace of this kind would cook it. */
    public static boolean cooks(ServerLevel level, RecipeType<? extends AbstractCookingRecipe> type, ItemStack s) {
        return recipe(level, type, s).isPresent();
    }

    /** Whether this room has a furnace of that kind. */
    public boolean has(ServerLevel level, RecipeType<?> type) {
        rescan(level);
        for (BlockPos p : furnaces) {
            if (level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f && typeOf(f) == type) {
                return true;
            }
        }
        return false;
    }

    /** The bellows: while the crew is here, every furnace in the room ticks twice. */
    @Override
    public void tick(ServerLevel level, int crewHere) {
        if (crewHere <= 0) {
            return;
        }
        for (BlockPos p : furnaces) {
            if (level.isLoaded(p) && level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f) {
                BlockState st = level.getBlockState(p);
                if (st.getBlock() instanceof AbstractFurnaceBlock) {
                    AbstractFurnaceBlockEntity.serverTick(level, p, st, f);
                }
            }
        }
    }

    @Override
    public void second(ServerLevel level, int crewHere) {
        rescan(level);
        if (++refineClock >= REFINE_EVERY) {
            refineClock = 0;
            refine(level);
        }
    }

    // ------------------------------------------------------------------ refining warpstone

    public static boolean refined(ItemStack s) {
        return Currencies.refined(s);
    }

    /** Whether the chambers can refine: a blast furnace, and an Ore Bin to put the blocks in. */
    public boolean canRefine(ServerLevel level) {
        return has(level, RecipeType.BLASTING) && !oreBins(level).isEmpty();
    }

    /** Refined warpstone on its way: warpstone blocks in the Ore Bin and the blast furnaces, refined waiting in their outputs. */
    int refinedComing(ServerLevel level) {
        rescan(level);
        int n = REFINED_PER_BLOCK * Bins.count(level, ores, s -> Currencies.block(s));
        for (BlockPos p : furnaces) {
            if (level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f) {
                if (Currencies.block(f.getItem(0))) {
                    n += REFINED_PER_BLOCK * f.getItem(0).getCount();
                }
                if (refined(f.getItem(2))) {
                    n += f.getItem(2).getCount();
                }
            }
        }
        return n;
    }

    /** Short of what the clan keeps (the Warlord's order and the rooms', the most of them): less what it holds and what is on its way. */
    private int keepShort(ServerLevel level, ItemStack r) {
        Works works = heart.works();
        WorkOrders.Order o = works.orders().get(r);
        Gear g = works.gear(level).get(Crafter.Key.of(r));
        int keep = Math.max(o == null ? 0 : o.keep, g == null ? 0 : g.keep());
        return Math.max(0, keep - works.holds(level, r) - refinedComing(level));
    }

    /** How much more refined warpstone the clan wants: short of its keep, and the Warlord's once-order. */
    public int refineNeed(ServerLevel level) {
        ItemStack r = new ItemStack(heart.refinedCurrency());
        WorkOrders.Order o = heart.works().orders().get(r);
        return keepShort(level, r) + (o == null ? 0 : o.once);
    }

    /**
     * Refine warpstone for the clan (see the class notes): while it wants more and the Heart's hoard holds more than
     * {@link #REFINE_RESERVE} and a block's worth, {@link #BLOCK_SHARDS} shards are pressed into a warpstone block and put
     * in the Ore Bin, where the crew takes it to a blast furnace. Returns how many blocks were pressed.
     */
    public int refine(ServerLevel level) {
        rescan(level);
        refineWanting = null;
        if (refineNeed(level) <= 0) {
            return 0;
        }
        if (!canRefine(level)) {
            refineWanting = "a blast furnace and an Ore Bin in the Furnace Chambers";
            return 0;
        }
        ItemStack r = new ItemStack(heart.refinedCurrency());
        int n = 0;
        for (int guard = 0; guard < 64; guard++) {
            boolean forKeep = keepShort(level, r) > 0;
            WorkOrders.Order o = heart.works().orders().get(r);
            if (!forKeep && (o == null || o.once <= 0)) {
                break;
            }
            if (heart.currency() < REFINE_RESERVE + BLOCK_SHARDS) {
                refineWanting = "warpstone in the Heart past " + (REFINE_RESERVE + BLOCK_SHARDS) + " to refine (it keeps " + REFINE_RESERVE + ")";
                break;
            }
            ItemStack block = new ItemStack(heart.currencyBlock());
            if (!Bins.hasRoom(level, ores, block)) {
                refineWanting = "room in the Ore Bin for warpstone to refine";
                break;
            }
            if (!heart.spendCurrency(BLOCK_SHARDS, room().displayName())) {
                break;
            }
            Bins.insert(level, ores, block);
            if (!forKeep) {
                heart.works().orders().made(r, REFINED_PER_BLOCK);    // on its way: counted off the once-order
            }
            pressed++;
            n++;
            heart.tally(claude.underempire.core.settlement.Settlement.Deed.REFINED, 1);
        }
        if (n > 0) {
            Bins.changed(level, ores);
            heart.setChanged();
        }
        return n;
    }

    @Override
    @Nullable
    public Chore nextChore(ServerLevel level, SettlerMob rat) {
        rescan(level);
        long now = level.getGameTime();
        List<BlockPos> order = new ArrayList<>(furnaces);
        order.sort(Comparator.comparingDouble(p -> p.distSqr(rat.blockPosition())));
        // 1. a furnace's finished work out to the Ingot Bin
        for (BlockPos p : order) {
            if (!free(p, now) || !(level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f)) {
                continue;
            }
            ItemStack out = f.getItem(2);
            if (out.isEmpty() || !(out.getCount() >= 8 || f.getItem(0).isEmpty() || out.getCount() >= out.getMaxStackSize() - 4)) {
                continue;
            }
            if (!Bins.hasRoom(level, ingots, out)) {
                continue;
            }
            return new Chore("emptying a furnace", p, 2.6, 6, p, (l, r) -> {
                if (!(l.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity ff) || ff.getItem(2).isEmpty()) {
                    return null;
                }
                ItemStack got = ff.removeItem(2, ff.getItem(2).getCount());
                smelted += got.getCount();
                r.carry(got);
                BlockPos bin = nearest(ingots, p);
                return bin == null ? null : new Chore("carrying the smelt to the Ingot Bin", bin, 2.6, 4, p, (l2, r2) -> {
                    ItemStack rest = store(l2, ingots, r2.carried());
                    r2.carry(ItemStack.EMPTY);
                    if (!rest.isEmpty()) {
                        r2.spawnAtLocation(rest);
                    }
                    return null;
                }).returning(bin);
            });
        }
        // 2. fuel to a furnace with something to cook that is burning out
        for (BlockPos p : order) {
            if (!free(p, now) || !(level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f)) {
                continue;
            }
            ItemStack fuelSlot = f.getItem(1);
            boolean hungry = fuelSlot.isEmpty() || fuelSlot.getCount() < 2 && !isLit(level, p);
            boolean work = !f.getItem(0).isEmpty();              // only a loaded furnace: fuel in an empty one is wasted
            if (!hungry || !work) {
                continue;
            }
            ItemStack kind = Bins.peek(level, fuel, s -> AbstractFurnaceBlockEntity.isFuel(s)
                    && (fuelSlot.isEmpty() || ItemStack.isSameItemSameComponents(s, fuelSlot)));
            if (kind.isEmpty()) {
                continue;
            }
            BlockPos bin = nearestWith(level, fuel, p, s -> ItemStack.isSameItemSameComponents(s, kind));   // the barrel that has it
            return carry("stoking a furnace", bin, s -> ItemStack.isSameItemSameComponents(s, kind), FUEL_LOAD, p, p,
                    (l, s) -> putIn(l, p, 1, s));
        }
        // 3. a load for a furnace with an empty (or like, and short) input: ore to the blast furnaces, food to the smokers
        order.sort(Comparator.comparingInt((BlockPos p) -> rank(level, p)).thenComparingDouble(p -> p.distSqr(rat.blockPosition())));
        for (BlockPos p : order) {
            if (!free(p, now) || !(level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f)) {
                continue;
            }
            ItemStack in = f.getItem(0);
            if (in.getCount() >= LOAD) {
                continue;
            }
            ItemStack kind = loadFor(level, typeOf(f), in);
            if (kind.isEmpty()) {
                continue;
            }
            ItemStack k = kind;
            BlockPos bin = nearestWith(level, ores, p, s -> ItemStack.isSameItemSameComponents(s, k));      // the barrel that has it
            return carry("loading a furnace", bin, s -> ItemStack.isSameItemSameComponents(s, k), LOAD - in.getCount(), p, p,
                    (l, s) -> putIn(l, p, 0, s));
        }
        return null;
    }

    /** Blast furnaces first, then smokers, then plain furnaces: the special furnaces get their loads before a plain one takes them. */
    private static int rank(ServerLevel level, BlockPos p) {
        if (!(level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f)) {
            return 9;
        }
        return f instanceof BlastFurnaceBlockEntity ? 0 : f instanceof SmokerBlockEntity ? 1 : 2;
    }

    /** Which furnace cooks it best: a blast furnace for ore and metal, a smoker for food, else a furnace. */
    public static RecipeType<? extends AbstractCookingRecipe> bestFor(ServerLevel level, ItemStack s) {
        if (cooks(level, RecipeType.BLASTING, s)) {
            return RecipeType.BLASTING;
        }
        if (cooks(level, RecipeType.SMOKING, s)) {
            return RecipeType.SMOKING;
        }
        return RecipeType.SMELTING;
    }

    private static boolean isLit(ServerLevel level, BlockPos p) {
        BlockState st = level.getBlockState(p);
        return st.hasProperty(AbstractFurnaceBlock.LIT) && st.getValue(AbstractFurnaceBlock.LIT);
    }

    /** Into a furnace's slot (0 input, 1 fuel), onto what is there. Returns what did not fit. */
    static ItemStack putIn(ServerLevel level, BlockPos p, int slot, ItemStack s) {
        if (s.isEmpty() || !(level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f)) {
            return s;
        }
        ItemStack have = f.getItem(slot);
        if (have.isEmpty()) {
            if (!f.canPlaceItem(slot, s)) {
                return s;
            }
            f.setItem(slot, s.copy());
            f.setChanged();
            return ItemStack.EMPTY;
        }
        if (!ItemStack.isSameItemSameComponents(have, s)) {
            return s;
        }
        int move = Math.min(s.getCount(), have.getMaxStackSize() - have.getCount());
        have.grow(move);
        f.setChanged();
        return s.copyWithCount(s.getCount() - move);
    }

    /**
     * The night's smelting: every furnace works {@code seconds} (twice over, the bellows) from the bins,
     * as it would have with the crew at it: loads from the Ore Bin, fuel from the Fuel Bin, its work into
     * the Ingot Bin.
     */
    @Override
    public void nightsWork(ServerLevel level, int seconds, List<Component> report) {
        rescan(level);
        int blocks = refine(level);
        if (blocks > 0) {
            report.add(Component.literal("The Furnace Chambers pressed " + blocks * BLOCK_SHARDS + " warpstone into " + blocks
                    + (blocks == 1 ? " block" : " blocks") + " to refine.").withStyle(ChatFormatting.DARK_GREEN));
        }
        int before = (int) smelted;
        for (BlockPos p : furnaces) {
            if (!(level.getBlockEntity(p) instanceof AbstractFurnaceBlockEntity f)) {
                continue;
            }
            RecipeType<? extends AbstractCookingRecipe> type = typeOf(f);
            int ticks = seconds * 20 * 2;
            int burn = 0;                                   // fuel ticks left in hand
            while (ticks > 0) {
                ItemStack in = f.getItem(0);
                if (in.isEmpty()) {
                    ItemStack kind = loadFor(level, type, ItemStack.EMPTY);   // the night's work too: the most precious first
                    ItemStack load = kind.isEmpty() ? ItemStack.EMPTY : Bins.take(level, ores, s -> ItemStack.isSameItemSameComponents(s, kind), 64);
                    if (load.isEmpty()) {
                        break;
                    }
                    f.setItem(0, load);
                    in = f.getItem(0);
                }
                Optional<RecipeHolder<AbstractCookingRecipe>> r = recipe(level, type, in);
                if (r.isEmpty()) {
                    break;
                }
                int cook = r.get().value().getCookingTime();
                while (burn < cook) {
                    ItemStack fs = f.getItem(1);
                    if (fs.isEmpty()) {
                        ItemStack more = Bins.take(level, fuel, AbstractFurnaceBlockEntity::isFuel, 64);
                        if (more.isEmpty()) {
                            break;
                        }
                        f.setItem(1, more);
                        fs = f.getItem(1);
                    }
                    burn += fs.getBurnTime(type) / (type == RecipeType.SMELTING ? 1 : 2);   // blast furnaces and smokers burn fuel twice as fast
                    ItemStack rem = fs.getCraftingRemainingItem();
                    fs.shrink(1);
                    if (fs.isEmpty()) {
                        f.setItem(1, rem);
                    }
                }
                if (burn < cook || ticks < cook) {
                    break;
                }
                ItemStack result = r.get().value().assemble(new SingleRecipeInput(in), level.registryAccess());
                ItemStack rest = store(level, ingots, result.copy());
                if (!rest.isEmpty()) {
                    ItemStack o = f.getItem(2);
                    if (o.isEmpty()) {
                        f.setItem(2, rest);
                    } else if (ItemStack.isSameItemSameComponents(o, rest) && o.getCount() + rest.getCount() <= o.getMaxStackSize()) {
                        o.grow(rest.getCount());
                    } else {
                        break;                                  // nowhere to put it: the furnace stops
                    }
                }
                in.shrink(1);
                burn -= cook;
                ticks -= cook;
                smelted++;
            }
            f.setChanged();
        }
        int n = (int) smelted - before;
        if (n > 0) {
            report.add(Component.literal("The furnaces smelted " + n + " loads in the night.").withStyle(ChatFormatting.DARK_GREEN));
        }
    }

    /**
     * Ore and raw metal for the Ore Bin, coal for the Fuel Bin; and whatever else smeltable the Warlord has dropped into
     * the Ore Bin (a cobblestone, sand, a log): the Forge is kept in that too, and turns the Hoard's stock of it into
     * stone, glass, charcoal.
     */
    @Override
    public List<Supply> supplies(ServerLevel level) {
        rescan(level);
        java.util.Set<net.minecraft.world.item.Item> kept = new java.util.HashSet<>();
        for (ItemStack s : Bins.stacks(level, ores)) {
            if (cooks(level, RecipeType.SMELTING, s)) {
                kept.add(s.getItem());
            }
        }
        return List.of(
                new Supply("ore" + (kept.isEmpty() ? "" : " and what you dropped in the Ore Bin"), ores, s -> kept.contains(s.getItem())
                        || (s.is(net.neoforged.neoforge.common.Tags.Items.RAW_MATERIALS) || s.is(net.neoforged.neoforge.common.Tags.Items.ORES))
                        && cooks(level, RecipeType.SMELTING, s), 64),
                new Supply("fuel", fuel, s -> s.is(net.minecraft.tags.ItemTags.COALS) || s.is(net.minecraft.world.item.Items.COAL_BLOCK), 32));
    }

    @Override
    public void wants(ServerLevel level, List<String> needs, List<String> boosts) {
        rescan(level);
        if (Bins.count(level, ores, s -> true) == 0) {
            needs.add("ore to smelt, in the Ore Bin");
        }
        if (!fuel.isEmpty() && Bins.count(level, fuel, s -> true) == 0) {
            needs.add("fuel in the Fuel Bin");
        }
        roomIn(level, ingots, INGOT_BIN, needs);
        if (refineWanting != null && refineNeed(level) > 0) {
            needs.add(refineWanting);
        }
    }

    @Override
    public Gauge gauge(ServerLevel level) {
        rescan(level);
        if (furnaces.isEmpty()) {
            return null;
        }
        int lit = 0;
        for (BlockPos p : furnaces) {
            if (isLit(level, p)) {
                lit++;
            }
        }
        return new Gauge(lit / (float) furnaces.size(), lit + " of " + furnaces.size() + " furnaces burning");
    }

    @Override
    public void describe(ServerLevel level, List<Component> out) {
        rescan(level);
        int lit = 0;
        for (BlockPos p : furnaces) {
            if (isLit(level, p)) {
                lit++;
            }
        }
        int waiting = Bins.count(level, ores, s -> true);
        out.add(Component.literal("Furnace Chambers: " + furnaces.size() + " furnaces, " + lit + " burning"
                + (waiting > 0 ? ", " + waiting + " waiting in the Ore Bin" : ", the Ore Bin is empty")
                + (fuel.isEmpty() || Bins.count(level, fuel, s -> true) > 0 ? "" : ", NO FUEL")
                + (refinedComing(level) > 0 ? "; refining warpstone (" + refinedComing(level) + " refined on its way)" : "")
                + (refineWanting != null && refineNeed(level) > 0 ? "; can't refine: needs " + refineWanting : "")).withStyle(ChatFormatting.DARK_GREEN));
    }

    @Override
    public net.minecraft.nbt.CompoundTag save() {
        var t = super.save();
        t.putLong("smelted", smelted);
        t.putLong("pressed", pressed);
        return t;
    }

    @Override
    public void load(net.minecraft.nbt.CompoundTag tag) {
        smelted = tag.getLong("smelted");
        pressed = tag.getLong("pressed");
    }
}
