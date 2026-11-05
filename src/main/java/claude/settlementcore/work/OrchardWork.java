package claude.settlementcore.work;

import claude.settlementcore.unit.SettlerMob;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.settlement.Building;
import claude.settlementcore.room.PlanPlacement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Warp-Orchard (2026-09-25: the user added Pam's HarvestCraft's fruit trees and asked for "Both": the orchard
 * and the cooks making Pam's dishes). Fruit trees and berries under warp-lamps. The crew plants a sapling
 * from the Sapling Crate on every empty tree bed (the template's "tree" stations) and feeds it warp-dust
 * (bone meal's work, as the Grove does its sprouts) until it shoots up; picks the fruit hanging in the
 * canopies when it is ripe (any mod's fruit that grows like a crop on a tree: Pam's HarvestCraft's, and
 * vanilla cocoa pods), hooking the high fruit down with a pole from the floor under it; shakes the trees
 * that bear none (a vanilla oak's only fruit is the apple) for an apple every {@link #SHAKE_TICKS}; picks
 * the sweet-berry bushes and the glow-berry vines, and dusts a picked vine so it fruits again. While the
 * crew is at work everything growing here grows about five times as fast (extra random ticks). What is
 * picked goes into the Fruit Bin; the horde carries it on (Works#haul: the larder, the Cook-Pits' Pantry,
 * the Hoard).
 */
public class OrchardWork extends RoomWork {

    public static final String SAPLING_CRATE = "Sapling Crate", FRUIT_BIN = "Fruit Bin";
    /** How often a sapling gets warp-dust. */
    static final int FEED_TICKS = 200;
    /** How often a fruitless tree is shaken for an apple. */
    static final int SHAKE_TICKS = 2400;
    /** How long a picked glow-berry vine waits for its warp-dust. */
    static final int VINE_TICKS = 900;
    /** Extra random ticks a second, per this many growing things. */
    static final int BOOST_PER = 12;
    /** How far below a hanging fruit the floor may be for a picker's pole, and how far out to the side. */
    static final int POLE = 10, REACH_OUT = 3;

    final List<Spot> beds;
    List<BlockPos> crates = List.of(), bins = List.of();
    /** Fruit in the canopies (crops hanging from a tree, cocoa pods), bushes, and glow-berry vines, as last found. */
    List<BlockPos> fruit = List.of(), bushes = List.of(), vines = List.of();
    final Map<BlockPos, Long> fedAt = new HashMap<>(), shakenAt = new HashMap<>(), pickedAt = new HashMap<>();
    long picked;

    public OrchardWork(Settlement heart, Building building, PlanPlacement placement) {
        super(heart, building, placement);
        beds = stations("tree");
    }

    @Override
    protected void scan(ServerLevel level) {
        crates = Bins.named(level, box(), SAPLING_CRATE);
        bins = Bins.named(level, box(), FRUIT_BIN);
        List<BlockPos> f = new ArrayList<>(), b = new ArrayList<>(), v = new ArrayList<>();
        var box = box();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.minY(); y <= box.maxY(); y++) {
                    if (!level.isLoaded(m.set(x, y, z))) {
                        continue;
                    }
                    BlockState s = level.getBlockState(m);
                    if (hangingFruit(level, m, s)) {
                        f.add(m.immutable());
                    } else if (s.getBlock() instanceof SweetBerryBushBlock) {
                        b.add(m.immutable());
                    } else if (s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT)) {
                        v.add(m.immutable());
                    }
                }
            }
        }
        fruit = f;
        bushes = b;
        vines = v;
    }

    /** Fruit grown on a tree: a crop that is not in a field (Pam's HarvestCraft's hang from the leaves), or a cocoa pod. */
    public static boolean hangingFruit(ServerLevel level, BlockPos p, BlockState s) {
        if (s.getBlock() instanceof CocoaBlock) {
            return true;
        }
        return s.getBlock() instanceof CropBlock && !(level.getBlockState(p.below()).getBlock() instanceof FarmlandBlock);
    }

    public static boolean ripe(BlockState s) {
        if (s.getBlock() instanceof CocoaBlock) {
            return s.getValue(CocoaBlock.AGE) >= CocoaBlock.MAX_AGE;
        }
        if (s.getBlock() instanceof SweetBerryBushBlock) {
            return s.getValue(SweetBerryBushBlock.AGE) >= SweetBerryBushBlock.MAX_AGE;
        }
        return s.getBlock() instanceof CropBlock c && c.isMaxAge(s);
    }

    /** A sapling that grows into a tree: vanilla's, Pam's HarvestCraft's (made from eight fruit and any sapling). */
    public static boolean sapling(ItemStack s) {
        return s.getItem() instanceof BlockItem bi && bi.getBlock() instanceof SaplingBlock;
    }

    /** How many of its beds have grown a tree. */
    public int trees(ServerLevel level) {
        int n = 0;
        for (Spot sp : beds) {
            if (level.isLoaded(sp.pos()) && level.getBlockState(sp.pos().above()).is(BlockTags.LOGS)) {
                n++;
            }
        }
        return n;
    }

    /** How much the orchard has given. */
    public long picked() {
        return picked;
    }

    /** The tree beds' earth (a tree stands on the block above). */
    public List<BlockPos> beds() {
        return beds.stream().map(Spot::pos).toList();
    }

    public List<BlockPos> bins(ServerLevel level) {
        rescan(level);
        return bins;
    }

    public List<BlockPos> crates(ServerLevel level) {
        rescan(level);
        return crates;
    }

    @Override
    public int crewWanted() {
        return fruit.size() + bushes.size() + vines.size() > 40 ? 3 : 2;
    }

    /** While the crew is at work everything growing here gets extra random ticks: about five times as fast. */
    @Override
    public void second(ServerLevel level, int crewHere) {
        rescan(level);
        if (crewHere <= 0) {
            return;
        }
        List<BlockPos> growing = new ArrayList<>(fruit);
        growing.addAll(bushes);
        for (Spot sp : beds) {
            growing.add(sp.pos().above());
        }
        if (growing.isEmpty()) {
            return;
        }
        int n = Math.max(1, growing.size() / BOOST_PER);
        for (int i = 0; i < n; i++) {
            BlockPos p = growing.get(level.getRandom().nextInt(growing.size()));
            if (!level.isLoaded(p)) {
                continue;                                        // (asked before the block: a read loads its chunk)
            }
            BlockState s = level.getBlockState(p);
            if (s.isRandomlyTicking() && !ripe(s)) {
                s.randomTick(level, p, level.getRandom());
                if (s.getBlock() instanceof SaplingBlock && level.getBlockState(p).is(BlockTags.LOGS)) {
                    rescanSoon();                                // a sapling grew by itself: its fruit, to be found
                }
            }
        }
    }

    @Override
    @Nullable
    public Chore nextChore(ServerLevel level, SettlerMob rat) {
        rescan(level);
        long now = level.getGameTime();
        BlockPos from = rat.blockPosition();
        // 1. ripe fruit in the canopies, hooked down from the floor under it
        Chore first = nearest(level, now, from, fruit, p -> ripe(level.getBlockState(p)) ? under(level, p) : null, (p, at) ->
                new Chore("picking fruit", at, 1.5, workTicks(rat, 14), p, (l, r) -> {
                    pick(l, p);
                    return null;
                }));
        if (first != null) {
            return first;
        }
        // 2. the bare tree beds planted (a tree is long in the growing)
        List<Spot> order = new ArrayList<>(beds);
        order.sort(Comparator.comparingDouble(sp -> sp.pos().distSqr(from)));
        for (Spot sp : order) {
            BlockPos ground = sp.pos(), up = ground.above();
            if (!level.isLoaded(up) || !free(ground, now)) {
                continue;
            }
            BlockState a = level.getBlockState(up);
            if (a.isAir() && level.getBlockState(ground).is(BlockTags.DIRT) && Bins.count(level, crates, OrchardWork::sapling) > 0) {
                BlockPos crate = nearest(crates, from);
                return new Chore("fetching a sapling", crate, 2.6, 6, ground, (l, r) -> {
                    ItemStack got = Bins.take(l, crates, OrchardWork::sapling, 1);
                    if (got.isEmpty()) {
                        return null;
                    }
                    r.carry(got);
                    return new Chore("planting a sapling", up, 2.4, workTicks(r, 10), ground, (l2, r2) -> {
                        ItemStack held = r2.carried();
                        r2.carry(ItemStack.EMPTY);
                        if (!plant(l2, ground, held)) {
                            ItemStack rest = Bins.insert(l2, crates, held);
                            if (!rest.isEmpty()) {
                                r2.spawnAtLocation((net.minecraft.server.level.ServerLevel) r2.level(), rest);
                            }
                        }
                        return null;
                    }).returning(crate);
                });
            }
        }
        Chore c;
        // 3. ripe berry bushes, from the path beside them (not in the thorns)
        c = nearest(level, now, from, bushes, p -> ripe(level.getBlockState(p)) ? beside(level, p) : null, (p, at) ->
                new Chore("picking berries", at, 1.5, workTicks(rat, 10), p, (l, r) -> {
                    pick(l, p);
                    return null;
                }));
        if (c != null) {
            return c;
        }
        // 4. glow berries on the vines
        c = nearest(level, now, from, vines, p -> CaveVines.hasGlowBerries(level.getBlockState(p)) ? under(level, p) : null, (p, at) ->
                new Chore("picking glow berries", at, 1.5, workTicks(rat, 10), p, (l, r) -> {
                    pick(l, p);
                    return null;
                }));
        if (c != null) {
            return c;
        }
        // 5. the saplings fed warp-dust, the fruitless trees shaken for apples
        for (Spot sp : order) {
            BlockPos ground = sp.pos(), up = ground.above();
            if (!level.isLoaded(up) || !free(ground, now)) {
                continue;
            }
            BlockState a = level.getBlockState(up);
            if (a.getBlock() instanceof SaplingBlock && now - fedAt.getOrDefault(ground, Long.MIN_VALUE / 2) >= FEED_TICKS) {
                return new Chore("feeding a sapling", up, 2.4, workTicks(rat, 20), ground, (l, r) -> {
                    fedAt.put(ground, l.getGameTime());
                    feed(l, ground);
                    return null;
                });
            }
            if (a.is(BlockTags.LOGS) && now - shakenAt.getOrDefault(ground, Long.MIN_VALUE / 2) >= SHAKE_TICKS && bearsNoFruit(ground)) {
                return new Chore("shaking a tree for apples", up, 2.4, workTicks(rat, 30), ground, (l, r) -> {
                    shakenAt.put(ground, l.getGameTime());
                    shake(l, ground);
                    return null;
                });
            }
        }
        // 6. a picked vine, dusted so it fruits again
        return nearest(level, now, from, vines, p -> {
            BlockState s = level.getBlockState(p);
            boolean due = (s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT)) && !CaveVines.hasGlowBerries(s)
                    && now - pickedAt.getOrDefault(p, Long.MIN_VALUE / 2) >= VINE_TICKS;
            return due ? under(level, p) : null;
        }, (p, at) -> new Chore("dusting a vine", at, 1.5, workTicks(rat, 12), p, (l, r) -> {
            dust(l, p);
            return null;
        }));
    }

    @FunctionalInterface
    private interface Make {
        Chore make(BlockPos job, BlockPos at);
    }

    /** The nearest free job of these that {@code where} finds somewhere to stand for, as a chore. */
    @Nullable
    private Chore nearest(ServerLevel level, long now, BlockPos from, List<BlockPos> jobs, Function<BlockPos, BlockPos> where, Make make) {
        BlockPos best = null, bestAt = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : jobs) {
            double d = p.distSqr(from);
            if (d >= bd || !level.isLoaded(p) || !free(p, now)) {
                continue;
            }
            BlockPos at = where.apply(p);
            if (at != null) {
                bd = d;
                best = p;
                bestAt = at;
            }
        }
        return best == null ? null : make.make(best, bestAt);
    }

    /**
     * Where a picker stands to reach a hanging thing with its pole: the nearest floor within {@link #REACH_OUT}
     * round, from the thing's own height down to {@link #POLE} below (straight under it if there is room; under a
     * low canopy there is not). The fruit can hang at the picker's own feet: a Pam's HarvestCraft fruit tree hangs
     * its ring of fruit four below the top of its trunk, and a trunk only four logs tall (one tree in six) sets the
     * whole ring on the floor, under the canopy's lowest and widest layer. Looked for only below the fruit, there
     * was no floor for any of it, and the crew never picked that tree (2026-09-26: the orchard test's "0 of 16
     * ripe Pam's fruit picked", when both its trees came up that short). A canopy is no floor: the crew can't climb
     * a tree, so a leaf top is never where it stands.
     */
    @Nullable
    public static BlockPos under(ServerLevel level, BlockPos p) {
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (int dx = -REACH_OUT; dx <= REACH_OUT; dx++) {
            for (int dz = -REACH_OUT; dz <= REACH_OUT; dz++) {
                int h = dx * dx + dz * dz;
                if (h > REACH_OUT * REACH_OUT || h >= bd) {
                    continue;
                }
                for (int dy = 0; dy <= POLE; dy++) {
                    BlockPos q = p.offset(dx, -dy, dz);
                    if (!level.isLoaded(q)) {
                        break;                                   // a column over the edge of the loaded ground: not looked at
                    }
                    if (SettlerMob.standable(level, q) && !level.getBlockState(q.below()).is(BlockTags.LEAVES)) {
                        bd = h;
                        best = q;
                        break;                                   // the highest floor in this column
                    }
                }
            }
        }
        return best;
    }

    /** A cell on the path beside a bush, to pick it from without walking into its thorns. */
    @Nullable
    static BlockPos beside(ServerLevel level, BlockPos p) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos q = p.relative(d);
            if (level.isLoaded(q) && SettlerMob.standable(level, q) && !(level.getBlockState(q).getBlock() instanceof SweetBerryBushBlock)) {
                return q;
            }
        }
        return null;
    }

    private static int workTicks(SettlerMob rat, int base) {
        return Math.max(4, Math.round(base / rat.tempo()));
    }

    /** Pick what is ripe at {@code p} into the Fruit Bin: the fruit or pod starts again, a bush goes back to bare, a vine goes dark. */
    boolean pick(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p)) {
            return false;
        }
        BlockState s = level.getBlockState(p);
        List<ItemStack> got = new ArrayList<>();
        BlockState after;
        if (s.getBlock() instanceof SweetBerryBushBlock) {
            if (!ripe(s)) {
                return false;
            }
            got.add(new ItemStack(Items.SWEET_BERRIES, 2 + level.getRandom().nextInt(2)));
            after = s.setValue(SweetBerryBushBlock.AGE, 1);
            level.playSound(null, p, SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES, SoundSource.BLOCKS, 1f, 0.8f + level.getRandom().nextFloat() * 0.4f);
        } else if (s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT)) {
            if (!CaveVines.hasGlowBerries(s)) {
                return false;
            }
            got.add(new ItemStack(Items.GLOW_BERRIES));
            after = s.setValue(CaveVines.BERRIES, false);
            pickedAt.put(p, level.getGameTime());
            level.playSound(null, p, SoundEvents.CAVE_VINES_PICK_BERRIES, SoundSource.BLOCKS, 1f, 0.8f + level.getRandom().nextFloat() * 0.4f);
        } else if (hangingFruit(level, p, s) && ripe(s)) {
            got.addAll(Block.getDrops(s, level, p, null));
            after = s.getBlock() instanceof CocoaBlock ? s.setValue(CocoaBlock.AGE, 0) : ((CropBlock) s.getBlock()).getStateForAge(0);
            level.levelEvent(2001, p, Block.getId(s));
        } else {
            return false;
        }
        level.setBlock(p, after, Block.UPDATE_ALL);
        for (ItemStack d : got) {
            if (d.isEmpty()) {
                continue;
            }
            int n = d.getCount();
            ItemStack rest = store(level, bins, d);
            if (!rest.isEmpty()) {
                Block.popResource(level, p, rest);
            }
            picked += n - rest.getCount();
        }
        return true;
    }

    /** A sapling on a bare bed; returns whether it took. */
    boolean plant(ServerLevel level, BlockPos ground, ItemStack held) {
        BlockPos up = ground.above();
        if (!sapling(held) || !level.isLoaded(up) || !level.getBlockState(up).isAir()) {
            return false;
        }
        BlockState s = ((BlockItem) held.getItem()).getBlock().defaultBlockState();
        if (!s.canSurvive(level, up)) {
            return false;
        }
        level.setBlock(up, s, Block.UPDATE_ALL);
        level.playSound(null, up, SoundEvents.GRASS_PLACE, SoundSource.BLOCKS, 0.8f, 1f);
        return true;
    }

    /** Warp-dust on a sapling: like bone meal, it grows about half the time. Returns whether it is a tree now. */
    boolean feed(ServerLevel level, BlockPos ground) {
        BlockPos up = ground.above();
        if (!level.isLoaded(up)) {
            return false;
        }
        BlockState a = level.getBlockState(up);
        if (!(a.getBlock() instanceof SaplingBlock sb)) {
            return false;
        }
        level.levelEvent(1505, up, 0);                          // the bone-meal sparkle
        if (sb.isBonemealSuccess(level, level.getRandom(), up, a)) {
            sb.performBonemeal(level, level.getRandom(), up, a);
        }
        if (level.getBlockState(up).is(BlockTags.LOGS)) {
            rescanSoon();                                        // the new tree's fruit, to be found
            return true;
        }
        return false;
    }

    /** Whether the tree on this bed bears no fruit of its own (fruit is looked for in its canopy, 3 round). */
    private boolean bearsNoFruit(BlockPos ground) {
        for (BlockPos f : fruit) {
            if (Math.abs(f.getX() - ground.getX()) <= 3 && Math.abs(f.getZ() - ground.getZ()) <= 3) {
                return false;
            }
        }
        return true;
    }

    /** A fruitless tree shaken: an apple from an oak's leaves (a vanilla oak's only fruit), nothing from other trees. */
    void shake(ServerLevel level, BlockPos ground) {
        BlockPos up = ground.above();
        boolean oak = false;
        for (BlockPos p : BlockPos.betweenClosed(up.offset(-3, 0, -3), up.offset(3, 8, 3))) {
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.OAK_LEAVES) || s.is(Blocks.DARK_OAK_LEAVES)) {
                oak = true;
                if (level.getRandom().nextInt(4) == 0) {
                    level.sendParticles(new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, s),
                            p.getX() + 0.5, p.getY() + 0.3, p.getZ() + 0.5, 2, 0.3, 0.1, 0.3, 0.02);
                }
            }
        }
        level.playSound(null, up, SoundEvents.AZALEA_LEAVES_HIT, SoundSource.BLOCKS, 1f, 0.8f);
        if (!oak) {
            return;
        }
        ItemStack rest = store(level, bins, new ItemStack(Items.APPLE));
        if (!rest.isEmpty()) {
            Block.popResource(level, up, rest);
        }
        picked += 1 - rest.getCount();
    }

    /** Warp-dust on a picked glow-berry vine: it fruits again. */
    void dust(ServerLevel level, BlockPos p) {
        if (!level.isLoaded(p)) {
            return;
        }
        BlockState s = level.getBlockState(p);
        if ((s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT)) && !CaveVines.hasGlowBerries(s)) {
            level.levelEvent(1505, p, 0);
            level.setBlock(p, s.setValue(CaveVines.BERRIES, true), Block.UPDATE_ALL);
        }
    }

    /** The night's orchard: everything grows and is picked as often as the crew would have managed. */
    @Override
    public void nightsWork(ServerLevel level, int seconds, List<Component> report) {
        rescan(level);
        long before = picked;
        int ticks = Math.min(80, Math.max(1, seconds * 5 / 68));             // random ticks each growing thing gets, ~5x
        List<BlockPos> growing = new ArrayList<>(fruit);
        growing.addAll(bushes);
        for (BlockPos p : growing) {
            if (!level.isLoaded(p)) {
                continue;
            }
            for (int i = 0; i < ticks; i++) {
                BlockState s = level.getBlockState(p);
                if (ripe(s)) {
                    pick(level, p);
                } else if (s.isRandomlyTicking()) {
                    s.randomTick(level, p, level.getRandom());
                }
            }
        }
        int rounds = Math.min(40, seconds * 20 / VINE_TICKS);
        for (BlockPos p : vines) {
            for (int i = 0; i < rounds && level.isLoaded(p); i++) {
                pick(level, p);
                dust(level, p);
            }
        }
        int feeds = seconds * 20 / FEED_TICKS, shakes = Math.min(20, seconds * 20 / SHAKE_TICKS);
        for (Spot sp : beds) {
            BlockPos ground = sp.pos(), up = ground.above();
            if (!level.isLoaded(up)) {
                continue;
            }
            if (level.getBlockState(up).isAir() && level.getBlockState(ground).is(BlockTags.DIRT)) {
                ItemStack s = Bins.take(level, crates, OrchardWork::sapling, 1);
                if (!s.isEmpty() && !plant(level, ground, s)) {
                    Bins.insert(level, crates, s);
                }
            }
            for (int i = 0; i < feeds && level.getBlockState(up).getBlock() instanceof SaplingBlock; i++) {
                feed(level, ground);
            }
        }
        rescanSoon();
        rescan(level);
        for (Spot sp : beds) {
            BlockPos ground = sp.pos();
            if (level.isLoaded(ground) && level.getBlockState(ground.above()).is(BlockTags.LOGS) && bearsNoFruit(ground)) {
                for (int i = 0; i < shakes; i++) {
                    shake(level, ground);
                }
            }
        }
        if (picked > before) {
            report.add(Component.literal("The orchard gave " + (picked - before) + " fruit.").withStyle(ChatFormatting.DARK_GREEN));
        }
    }

    @Override
    public List<Supply> supplies(ServerLevel level) {
        rescan(level);
        return List.of(new Supply("saplings", crates, OrchardWork::sapling, 16));
    }

    @Override
    public void wants(ServerLevel level, List<String> needs, List<String> boosts) {
        rescan(level);
        int bare = 0;
        for (Spot sp : beds) {
            if (!level.isLoaded(sp.pos())) {
                continue;
            }
            BlockState a = level.getBlockState(sp.pos().above());
            if (a.isAir() && level.getBlockState(sp.pos()).is(BlockTags.DIRT)) {
                bare++;
            }
        }
        if (bare > 0 && Bins.count(level, crates, OrchardWork::sapling) == 0) {
            needs.add("saplings in the Sapling Crate (" + bare + (bare == 1 ? " bare bed)" : " bare beds)"));
        }
        roomIn(level, bins, FRUIT_BIN, needs);
    }

    @Override
    public void describe(ServerLevel level, List<Component> out) {
        rescan(level);
        int trees = 0, saplings = 0, ripe = 0, berried = 0;
        for (Spot sp : beds) {
            if (!level.isLoaded(sp.pos())) {
                continue;
            }
            BlockState a = level.getBlockState(sp.pos().above());
            if (a.is(BlockTags.LOGS)) {
                trees++;
            } else if (a.getBlock() instanceof SaplingBlock) {
                saplings++;
            }
        }
        for (BlockPos p : fruit) {
            if (level.isLoaded(p) && ripe(level.getBlockState(p))) {
                ripe++;
            }
        }
        for (BlockPos p : bushes) {
            if (level.isLoaded(p) && ripe(level.getBlockState(p))) {
                berried++;
            }
        }
        for (BlockPos p : vines) {
            if (level.isLoaded(p) && CaveVines.hasGlowBerries(level.getBlockState(p))) {
                berried++;
            }
        }
        out.add(Component.literal("Warp-Orchard: " + trees + " trees, " + saplings + " saplings, " + (beds.size() - trees - saplings)
                + " beds bare; " + fruit.size() + " fruit on the trees (" + ripe + " ripe), " + berried + " berries ripe; " + picked + " picked")
                .withStyle(ChatFormatting.DARK_GREEN));
    }

    @Override
    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("picked", picked);
        return t;
    }

    @Override
    public void load(CompoundTag t) {
        picked = t.getLongOr("picked", 0L);
    }
}
