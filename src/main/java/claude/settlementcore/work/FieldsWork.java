package claude.settlementcore.work;

import claude.settlementcore.unit.SettlerMob;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.room.PlanPlacement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Slave Fields: farmland under warp-lamps. While its crew is at work the lamps and the slaves' tending
 * grow every crop about four times as fast as it would grow on the surface (extra random ticks). The
 * crew harvests what is ripe (a few neighbouring plots at a time), sows each plot again from its own
 * harvest, sows empty plots from the Harvest Bin, and hoes any plot that has dried back to dirt. A plot
 * keeps whatever crop was last planted on it, any mod's (Pam's HarvestCraft's too). The
 * harvest goes into the Harvest Bin; the horde carries it on from there (Works#haul: the larder first,
 * then the Beast Pens' fodder, then the Hoard).
 */
public class FieldsWork extends RoomWork {

    public static final String HARVEST_BIN = "Harvest Bin";
    /** Extra random ticks a second, per this many plots: about 4x a crop's growth on the surface. */
    static final int BOOST_PER = 22;

    /** Every plot (the farmland cell), and what grew there last. */
    final Map<BlockPos, Block> plots = new HashMap<>();
    List<BlockPos> bins = List.of();
    long harvested;

    public FieldsWork(Settlement heart, Building building, PlanPlacement placement) {
        super(heart, building, placement);
    }

    @Override
    protected void scan(ServerLevel level) {
        bins = Bins.named(level, box(), HARVEST_BIN);
        var b = box();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = b.minX(); x <= b.maxX(); x++) {
            for (int z = b.minZ(); z <= b.maxZ(); z++) {
                for (int y = b.minY(); y <= b.maxY(); y++) {
                    m.set(x, y, z);
                    if (!level.isLoaded(m)) {
                        continue;
                    }
                    BlockState s = level.getBlockState(m);
                    if (s.getBlock() instanceof FarmlandBlock) {
                        BlockState above = level.getBlockState(m.above());
                        Block had = plots.get(m);
                        plots.put(m.immutable(), above.getBlock() instanceof CropBlock ? above.getBlock() : had == null ? Blocks.WHEAT : had);
                    }
                }
            }
        }
    }

    public List<BlockPos> bins(ServerLevel level) {
        rescan(level);
        return bins;
    }

    @Override
    public int crewWanted() {
        return plots.size() > 150 ? 3 : 2;
    }

    @Override
    public void second(ServerLevel level, int crewHere) {
        rescan(level);
        if (crewHere <= 0 || plots.isEmpty()) {
            return;
        }
        List<BlockPos> all = new ArrayList<>(plots.keySet());
        int n = Math.max(1, all.size() / BOOST_PER);
        for (int i = 0; i < n; i++) {
            BlockPos crop = all.get(level.getRandom().nextInt(all.size())).above();
            if (!level.isLoaded(crop)) {
                continue;                                        // (asked before the block: a read loads its chunk)
            }
            BlockState s = level.getBlockState(crop);
            if (s.getBlock() instanceof CropBlock c && !c.isMaxAge(s)) {
                s.randomTick(level, crop, level.getRandom());
            }
        }
    }

    private static Block seedless(Block b) {
        return b instanceof CropBlock ? b : Blocks.WHEAT;
    }

    /**
     * Whether {@code s} plants the crop {@code c}: its seed, or the crop itself where that is how it is sown
     * (a carrot; Pam's HarvestCraft's tomatoes, whose plants drop only tomatoes although it has tomato seeds too).
     */
    public static boolean sows(ItemStack s, Block c) {
        return s.getItem() instanceof BlockItem bi && bi.getBlock() == c;
    }

    @Override
    @Nullable
    public Chore nextChore(ServerLevel level, SettlerMob rat) {
        rescan(level);
        long now = level.getGameTime();
        List<BlockPos> order = new ArrayList<>(plots.keySet());
        order.sort(Comparator.comparingDouble(p -> p.distSqr(rat.blockPosition())));
        for (BlockPos plot : order) {
            BlockPos crop = plot.above();
            if (!level.isLoaded(crop)) {
                continue;
            }
            BlockState s = level.getBlockState(crop);
            if (s.getBlock() instanceof CropBlock c && c.isMaxAge(s) && free(plot, now)) {
                return new Chore("reaping", crop, 2.2, workTicks(rat, 12), plot, (l, r) -> {
                    int n = 0;
                    for (BlockPos p : order) {                   // the ripe plots within reach of where it stands
                        if (n >= 4 || p.distSqr(plot) > 5) {
                            continue;
                        }
                        if (reap(l, p)) {
                            n++;
                        }
                    }
                    return null;
                });
            }
        }
        for (BlockPos plot : order) {
            BlockPos crop = plot.above();
            if (!level.isLoaded(plot)) {
                continue;
            }
            BlockState ground = level.getBlockState(plot);
            BlockState above = level.getBlockState(crop);
            if (!free(plot, now) || !above.isAir()) {
                continue;
            }
            if (ground.getBlock() instanceof FarmlandBlock) {
                Block kind = seedless(plots.get(plot));
                if (Bins.count(level, bins, st -> sows(st, kind)) > 0) {
                    return new Chore("sowing", crop, 2.2, workTicks(rat, 8), plot, (l, r) -> {
                        if (l.isLoaded(crop) && l.getBlockState(crop).isAir() && l.getBlockState(plot).getBlock() instanceof FarmlandBlock
                                && !Bins.take(l, bins, st -> sows(st, kind), 1).isEmpty()) {
                            l.setBlock(crop, kind.defaultBlockState(), Block.UPDATE_ALL);
                        }
                        return null;
                    });
                }
            } else if (ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT) || ground.is(Blocks.ROOTED_DIRT)) {
                return new Chore("hoeing", crop, 2.2, workTicks(rat, 10), plot, (l, r) -> {
                    if (l.isLoaded(crop) && l.getBlockState(crop).isAir()) {
                        l.setBlock(plot, Blocks.FARMLAND.defaultBlockState(), Block.UPDATE_ALL);
                        l.playSound(null, plot, SoundEvents.HOE_TILL, SoundSource.BLOCKS, 0.8f, 1f);
                    }
                    return null;
                });
            }
        }
        return null;
    }

    private static int workTicks(SettlerMob rat, int base) {
        return Math.max(4, Math.round(base / rat.tempo()));
    }

    /** Reap a ripe plot: its harvest into the Harvest Bin, one of its own seeds (or one from the bin) sown again. */
    boolean reap(ServerLevel level, BlockPos plot) {
        BlockPos crop = plot.above();
        if (!level.isLoaded(crop)) {
            return false;
        }
        BlockState s = level.getBlockState(crop);
        if (!(s.getBlock() instanceof CropBlock c) || !c.isMaxAge(s)) {
            return false;
        }
        List<ItemStack> drops = new ArrayList<>(Block.getDrops(s, level, crop, null));
        boolean sown = false;
        for (ItemStack d : drops) {
            if (!sown && sows(d, c)) {
                d.shrink(1);
                sown = true;
            }
        }
        if (!sown) {
            sown = !Bins.take(level, bins, st -> sows(st, c), 1).isEmpty();
        }
        level.levelEvent(2001, crop, Block.getId(s));
        level.setBlock(crop, sown ? c.getStateForAge(0) : Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        for (ItemStack d : drops) {
            if (!d.isEmpty()) {
                ItemStack rest = store(level, bins, d);
                if (!rest.isEmpty()) {
                    Block.popResource(level, crop, rest);
                }
                harvested += d.getCount() - rest.getCount();
            }
        }
        plots.put(plot, c);
        return true;
    }

    /** The night's growing and reaping: every crop grows as it would have with the crew at it, and whatever ripens is reaped. */
    @Override
    public void nightsWork(ServerLevel level, int seconds, List<Component> report) {
        rescan(level);
        long before = harvested;
        // on average a block gets a random tick every ~68 s at the default tick speed; the fields' tending is ~4x
        int ticks = Math.min(60, Math.max(1, seconds * 4 / 68));
        for (BlockPos plot : new ArrayList<>(plots.keySet())) {
            BlockPos crop = plot.above();
            if (!level.isLoaded(crop)) {
                continue;
            }
            for (int i = 0; i < ticks; i++) {
                BlockState s = level.getBlockState(crop);
                if (!(s.getBlock() instanceof CropBlock c)) {
                    break;
                }
                if (c.isMaxAge(s)) {
                    reap(level, plot);
                } else {
                    s.randomTick(level, crop, level.getRandom());
                }
            }
        }
        if (harvested > before) {
            report.add(Component.literal("The fields gave " + (harvested - before) + " crops.").withStyle(ChatFormatting.DARK_GREEN));
        }
    }

    /** Seed for the plots' crops, in the Harvest Bin (the reapers sow from it). */
    @Override
    public List<Supply> supplies(ServerLevel level) {
        rescan(level);
        java.util.Set<Block> kinds = new java.util.HashSet<>();
        for (Block b : plots.values()) {
            kinds.add(seedless(b));
        }
        return List.of(new Supply("seed", bins, s -> {
            for (Block k : kinds) {
                if (sows(s, k)) {
                    return true;
                }
            }
            return false;
        }, Works.SEED_KEEP));
    }

    @Override
    public void wants(ServerLevel level, List<String> needs, List<String> boosts) {
        rescan(level);
        java.util.Map<String, Integer> bare = new java.util.TreeMap<>();
        for (var e : plots.entrySet()) {
            BlockPos plot = e.getKey();
            Block kind = seedless(e.getValue());
            if (level.isLoaded(plot) && level.getBlockState(plot.above()).isAir() && level.getBlockState(plot).getBlock() instanceof net.minecraft.world.level.block.FarmlandBlock
                    && Bins.count(level, bins, st -> sows(st, kind)) == 0) {
                bare.merge(kind.getName().getString(), 1, Integer::sum);
            }
        }
        bare.forEach((crop, n) -> needs.add(crop + " seed for " + n + (n == 1 ? " bare plot" : " bare plots")));
        roomIn(level, bins, HARVEST_BIN, needs);
    }

    @Override
    public Gauge gauge(ServerLevel level) {
        rescan(level);
        int ripe = 0, growing = 0;
        for (BlockPos p : plots.keySet()) {
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p.above());
            if (s.getBlock() instanceof CropBlock c) {
                if (c.isMaxAge(s)) {
                    ripe++;
                } else {
                    growing++;
                }
            }
        }
        return plots.isEmpty() ? null : new Gauge((ripe + growing) / (float) plots.size(), (ripe + growing) + " of " + plots.size()
                + " plots sown, " + ripe + " ripe");
    }

    @Override
    public void describe(ServerLevel level, List<Component> out) {
        rescan(level);
        int ripe = 0, growing = 0;
        for (BlockPos p : plots.keySet()) {
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p.above());
            if (s.getBlock() instanceof CropBlock c) {
                if (c.isMaxAge(s)) {
                    ripe++;
                } else {
                    growing++;
                }
            }
        }
        out.add(Component.literal(room().displayName() + ": " + plots.size() + " plots, " + growing + " growing, " + ripe + " ripe; "
                + harvested + " reaped").withStyle(ChatFormatting.DARK_GREEN));
    }

    @Override
    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("harvested", harvested);
        return t;
    }

    @Override
    public void load(CompoundTag t) {
        harvested = t.getLongOr("harvested", 0L);
    }
}
