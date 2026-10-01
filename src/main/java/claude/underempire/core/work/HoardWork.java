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
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The Clan Hoard: the clan's storehouse. The horde hauls the other rooms' work here (Works#haul) and
 * sorts it into the barrels named for it, one colour of banner over each section; anything that fits
 * no section, or finds its own full, goes to "Spoils", and then anywhere there is room. Every container
 * in the Hoard is the clan's: an unnamed one the Warlord sets down counts as Spoils. A hoard-keeper
 * tends it, carrying anything left in the wrong section to the right one.
 */
public class HoardWork extends RoomWork {

    public enum Kind {
        METALS("Metals & Gems"), STONE("Stone"), FOOD("Food"), TIMBER("Timber"), SPOILS("Spoils");

        public final String bin;

        Kind(String bin) {
            this.bin = bin;
        }
    }

    final Map<Kind, List<BlockPos>> sections = new EnumMap<>(Kind.class);
    List<BlockPos> all = List.of();

    public HoardWork(Settlement heart, Building building, PlanPlacement placement) {
        super(heart, building, placement);
    }

    @Override
    protected void scan(ServerLevel level) {
        sections.clear();
        for (Kind k : Kind.values()) {
            sections.put(k, new ArrayList<>());
        }
        all = Bins.containers(level, box());
        for (BlockPos p : all) {
            String name = Bins.nameOf(level, p);
            Kind k = Kind.SPOILS;
            for (Kind kk : Kind.values()) {
                if (kk.bin.equalsIgnoreCase(name)) {
                    k = kk;
                }
            }
            sections.get(k).add(p);
        }
    }

    /** Which section a thing belongs in: the settlement's own currency (its unit, its refined form) is metal whatever it is made of. */
    public static Kind kind(Settlement heart, ItemStack s) {
        if (s.is(Tags.Items.INGOTS) || s.is(Tags.Items.RAW_MATERIALS) || s.is(Tags.Items.GEMS) || s.is(Tags.Items.NUGGETS)
                || s.is(Tags.Items.ORES) || s.is(Tags.Items.DUSTS) || s.is(ItemTags.COALS) || s.is(Tags.Items.STORAGE_BLOCKS)
                || s.is(Items.FLINT) || s.is(Items.AMETHYST_SHARD) || s.is(Items.QUARTZ)
                || Currencies.unit(heart, s) || Currencies.refined(heart, s)) {
            return Kind.METALS;
        }
        if (s.has(net.minecraft.core.component.DataComponents.FOOD) || s.is(Tags.Items.CROPS) || s.is(Tags.Items.SEEDS)
                || s.is(Items.EGG) || s.is(Items.SUGAR) || s.is(Items.WHEAT) || s.is(Items.HAY_BLOCK) || s.is(Items.MILK_BUCKET)) {
            return Kind.FOOD;
        }
        if (s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS) || s.is(ItemTags.SAPLINGS) || s.is(Tags.Items.RODS_WOODEN)
                || s.is(ItemTags.WOODEN_SLABS) || s.is(Items.BAMBOO) || s.is(Items.WARPED_FUNGUS) || s.is(Items.CRIMSON_FUNGUS)
                || s.is(Items.SHROOMLIGHT) || s.is(Items.WARPED_WART_BLOCK) || s.is(Items.NETHER_WART_BLOCK)) {
            return Kind.TIMBER;
        }
        if (s.is(Tags.Items.COBBLESTONES) || s.is(Tags.Items.STONES) || s.is(Tags.Items.GRAVELS) || s.is(Tags.Items.SANDS)
                || s.is(ItemTags.DIRT) || s.is(Items.CLAY_BALL) || s.is(Items.CLAY) || s.is(Items.CALCITE) || s.is(Items.TUFF)
                || s.is(Items.NETHERRACK) || s.is(Items.MUD) || s.getItem() instanceof BlockItem bi
                && bi.getBlock().defaultBlockState().is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)) {
            return Kind.STONE;
        }
        return Kind.SPOILS;
    }

    /** Into the Hoard, in its own section if there is room, else Spoils, else anywhere. Returns what did not fit. */
    public ItemStack put(ServerLevel level, ItemStack s) {
        rescan(level);
        ItemStack rest = store(level, sections.getOrDefault(kind(heart, s), List.of()), s);
        if (!rest.isEmpty()) {
            rest = store(level, sections.getOrDefault(Kind.SPOILS, List.of()), rest);
        }
        if (!rest.isEmpty()) {
            rest = store(level, all, rest);
        }
        return rest;
    }

    public boolean hasRoom(ServerLevel level, ItemStack s) {
        rescan(level);
        return Bins.hasRoom(level, all, s);
    }

    @Override
    public int crewWanted() {
        return 1;
    }

    /** The hoard-keeper: now and then a stack left in the wrong section goes to its own. */
    @Override
    @Nullable
    public Chore nextChore(ServerLevel level, SettlerMob rat) {
        rescan(level);
        long now = level.getGameTime();
        for (Kind k : Kind.values()) {
            if (k == Kind.SPOILS) {
                continue;                                        // Spoils takes anything: nothing there is misplaced
            }
            for (Kind wrong : Kind.values()) {
                if (wrong == k) {
                    continue;
                }
                for (BlockPos from : sections.getOrDefault(wrong, List.of())) {
                    Container c = Bins.at(level, from);
                    if (c == null || !free(from, now)) {
                        continue;
                    }
                    for (int i = 0; i < c.getContainerSize(); i++) {
                        ItemStack have = c.getItem(i);
                        if (!have.isEmpty() && kind(heart, have) == k && Bins.hasRoom(level, sections.get(k), have)) {
                            BlockPos to = nearest(sections.get(k), from);
                            ItemStack like = have.copy();
                            return carry("sorting the hoard", from, st -> ItemStack.isSameItemSameComponents(st, like), 64, to, from,
                                    (l, st) -> store(l, sections.get(k), st));
                        }
                    }
                }
            }
        }
        return null;
    }

    /** Every barrel of the Hoard: what the horde draws the rooms' supply from (Works#supply). */
    public List<BlockPos> barrels(ServerLevel level) {
        rescan(level);
        return all;
    }

    @Override
    public void wants(ServerLevel level, List<String> needs, List<String> boosts) {
        Gauge g = gauge(level);
        if (g != null && g.fill() >= 0.9f) {
            needs.add("room: dig another Clan Hoard");
        }
    }

    @Override
    public Gauge gauge(ServerLevel level) {
        rescan(level);
        int slots = 0, used = 0;
        for (BlockPos p : all) {
            Container c = Bins.at(level, p);
            if (c != null) {
                slots += c.getContainerSize();
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (!c.getItem(i).isEmpty()) {
                        used++;
                    }
                }
            }
        }
        return slots == 0 ? null : new Gauge(used / (float) slots, used + " of " + slots + " slots full");
    }

    @Override
    public void describe(ServerLevel level, List<Component> out) {
        rescan(level);
        int slots = 0, used = 0;
        for (BlockPos p : all) {
            Container c = Bins.at(level, p);
            if (c != null) {
                slots += c.getContainerSize();
                for (int i = 0; i < c.getContainerSize(); i++) {
                    if (!c.getItem(i).isEmpty()) {
                        used++;
                    }
                }
            }
        }
        out.add(Component.literal("Clan Hoard: " + all.size() + " barrels, " + used + "/" + slots + " slots full")
                .withStyle(ChatFormatting.DARK_GREEN));
    }
}
