package claude.underempire.core.work;

import claude.underempire.core.unit.SettlerMob;
import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.settlement.Building;
import claude.underempire.core.room.PlanPlacement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FungusBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fungus Grove: the warren's timber. Each planting spot (the template's "fungus" stations, nylium) grows
 * a giant fungus: the crew feeds a sprout warp-dust (bone meal's work, 40% a go, as vanilla) every
 * {@link #FEED_TICKS} until it shoots up; a grown one is felled whole (stems, shroomlights and an
 * eighth of the wart into the Timber Bin; vines and the rest of the cap are gnawed away) and the spot
 * sown with a fresh sprout of its nylium's kind. The Hoard takes the timber from there.
 */
public class GroveWork extends RoomWork {

    public static final String TIMBER_BIN = "Timber Bin";
    static final int FEED_TICKS = 300;
    static final int FELL_MAX = 900;

    List<Spot> spots;
    List<BlockPos> bins = List.of();
    final Map<BlockPos, Long> fedAt = new HashMap<>();
    long stems;

    public GroveWork(Settlement heart, Building building, PlanPlacement placement) {
        super(heart, building, placement);
        spots = stations("fungus");
    }

    @Override
    protected void scan(ServerLevel level) {
        bins = Bins.named(level, box(), TIMBER_BIN);
    }

    public List<BlockPos> bins(ServerLevel level) {
        rescan(level);
        return bins;
    }

    @Override
    public int crewWanted() {
        return 2;
    }

    @Override
    public void second(ServerLevel level, int crewHere) {
        rescan(level);
    }

    private static boolean stem(BlockState s) {
        return s.is(Blocks.WARPED_STEM) || s.is(Blocks.CRIMSON_STEM);
    }

    private static boolean nylium(BlockState s) {
        return s.is(Blocks.WARPED_NYLIUM) || s.is(Blocks.CRIMSON_NYLIUM);
    }

    @Override
    @Nullable
    public Chore nextChore(ServerLevel level, SettlerMob rat) {
        rescan(level);
        long now = level.getGameTime();
        for (Spot sp : spots) {
            BlockPos ground = sp.pos();
            BlockPos up = ground.above();
            if (!level.isLoaded(up) || !free(ground, now)) {
                continue;
            }
            BlockState g = level.getBlockState(ground);
            BlockState a = level.getBlockState(up);
            if (stem(a)) {
                return new Chore("felling a fungus", up, 2.4, workTicks(rat, 60), ground, (l, r) -> {
                    fell(l, ground);
                    plant(l, ground);
                    return null;
                });
            }
            if (nylium(g) && a.isAir()) {
                return new Chore("planting a sprout", up, 2.4, workTicks(rat, 10), ground, (l, r) -> {
                    plant(l, ground);
                    return null;
                });
            }
            if (a.getBlock() instanceof FungusBlock && now - fedAt.getOrDefault(ground, Long.MIN_VALUE / 2) >= FEED_TICKS) {
                return new Chore("feeding a sprout", up, 2.4, workTicks(rat, 20), ground, (l, r) -> {
                    fedAt.put(ground, l.getGameTime());
                    feed(l, ground);
                    return null;
                });
            }
        }
        return null;
    }

    private static int workTicks(SettlerMob rat, int base) {
        return Math.max(4, Math.round(base / rat.tempo()));
    }

    /** A sprout of the spot's own kind on bare nylium. */
    void plant(ServerLevel level, BlockPos ground) {
        BlockState g = level.getBlockState(ground);
        BlockPos up = ground.above();
        if (!nylium(g) || !level.getBlockState(up).isAir()) {
            return;
        }
        level.setBlock(up, (g.is(Blocks.WARPED_NYLIUM) ? Blocks.WARPED_FUNGUS : Blocks.CRIMSON_FUNGUS).defaultBlockState(), Block.UPDATE_ALL);
        level.playSound(null, up, SoundEvents.FUNGUS_PLACE, SoundSource.BLOCKS, 0.8f, 1f);
    }

    /** Warp-dust on a sprout: like bone meal, it shoots up four times in ten. Returns whether it grew. */
    boolean feed(ServerLevel level, BlockPos ground) {
        BlockPos up = ground.above();
        BlockState a = level.getBlockState(up);
        if (!(a.getBlock() instanceof FungusBlock f) || !f.isValidBonemealTarget(level, up, a)) {
            return false;
        }
        level.levelEvent(1505, up, 0);                          // the bone-meal sparkle
        if (f.isBonemealSuccess(level, level.random, up, a)) {
            f.performBonemeal(level, level.random, up, a);
        }
        return stem(level.getBlockState(up));
    }

    /** Fell the fungus standing on {@code ground}: the whole of it, stem to cap. */
    void fell(ServerLevel level, BlockPos ground) {
        Deque<BlockPos> todo = new ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        todo.add(ground.above());
        seen.add(ground.above().asLong());
        List<ItemStack> got = new ArrayList<>();
        int wart = 0, n = 0;
        var room = box().inflatedBy(2);
        while (!todo.isEmpty() && n < FELL_MAX) {
            BlockPos p = todo.poll();
            BlockState s = level.getBlockState(p);
            boolean part = stem(s) || s.is(Blocks.WARPED_WART_BLOCK) || s.is(Blocks.NETHER_WART_BLOCK) || s.is(Blocks.SHROOMLIGHT)
                    || s.is(Blocks.WEEPING_VINES) || s.is(Blocks.WEEPING_VINES_PLANT) || s.is(Blocks.TWISTING_VINES)
                    || s.is(Blocks.TWISTING_VINES_PLANT);
            if (!part || !room.isInside(p) || Math.abs(p.getX() - ground.getX()) > 5 || Math.abs(p.getZ() - ground.getZ()) > 5) {
                continue;
            }
            n++;
            if (stem(s) || s.is(Blocks.SHROOMLIGHT)) {
                got.add(new ItemStack(s.getBlock().asItem()));
            } else if ((s.is(Blocks.WARPED_WART_BLOCK) || s.is(Blocks.NETHER_WART_BLOCK)) && ++wart % 8 == 0) {
                got.add(new ItemStack(s.getBlock().asItem()));
            }
            if (n % 6 == 1) {
                level.levelEvent(2001, p, Block.getId(s));
            }
            level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            for (Direction d : Direction.values()) {
                BlockPos q = p.relative(d);
                if (seen.add(q.asLong())) {
                    todo.add(q);
                }
            }
        }
        level.playSound(null, ground.above(), SoundEvents.STEM_BREAK, SoundSource.BLOCKS, 1.2f, 0.7f);
        for (ItemStack s : merge(got)) {
            if (stem(Block.byItem(s.getItem()).defaultBlockState())) {
                stems += s.getCount();
            }
            ItemStack rest = store(level, bins, s);
            if (!rest.isEmpty()) {
                Block.popResource(level, ground.above(), rest);
            }
        }
    }

    private static List<ItemStack> merge(List<ItemStack> in) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : in) {
            boolean put = false;
            for (ItemStack o : out) {
                if (ItemStack.isSameItemSameComponents(o, s) && o.getCount() < o.getMaxStackSize()) {
                    o.grow(1);
                    put = true;
                    break;
                }
            }
            if (!put) {
                out.add(s.copy());
            }
        }
        return out;
    }

    /** The night's growing: each spot is fed and felled as often as the crew would have (at most twice over). */
    @Override
    public void nightsWork(ServerLevel level, int seconds, List<Component> report) {
        rescan(level);
        long before = stems;
        int feeds = seconds * 20 / FEED_TICKS;
        for (Spot sp : spots) {
            BlockPos ground = sp.pos();
            if (!level.isLoaded(ground.above())) {
                continue;
            }
            int felled = 0;
            for (int i = 0; i < feeds && felled < 2; i++) {
                BlockState a = level.getBlockState(ground.above());
                if (stem(a)) {
                    fell(level, ground);
                    felled++;
                    plant(level, ground);
                } else if (a.isAir()) {
                    plant(level, ground);
                } else {
                    feed(level, ground);
                }
            }
        }
        if (stems > before) {
            report.add(Component.literal("The grove gave " + (stems - before) + " stems of timber.").withStyle(ChatFormatting.DARK_GREEN));
        }
    }

    @Override
    public void wants(ServerLevel level, List<String> needs, List<String> boosts) {
        roomIn(level, bins, TIMBER_BIN, needs);
    }

    @Override
    public void describe(ServerLevel level, List<Component> out) {
        int grown = 0, sprouts = 0;
        for (Spot sp : spots) {
            BlockState a = level.getBlockState(sp.pos().above());
            if (stem(a)) {
                grown++;
            } else if (a.getBlock() instanceof FungusBlock) {
                sprouts++;
            }
        }
        out.add(Component.literal("Fungus Grove: " + grown + " grown, " + sprouts + " sprouting; " + stems + " stems felled")
                .withStyle(ChatFormatting.DARK_GREEN));
    }

    @Override
    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putLong("stems", stems);
        return t;
    }

    @Override
    public void load(CompoundTag t) {
        stems = t.getLong("stems");
    }
}
