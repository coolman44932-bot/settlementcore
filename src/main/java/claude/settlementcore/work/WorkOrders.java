package claude.settlementcore.work;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Warlord's orders to the Warlock's Workshop (user, 2026-09-26: "can we automate weapon making yet" → "yeah do it"),
 * set in the Hoard-Ledger: of each thing, how many the clan should keep ({@link Order#keep}: whenever it holds fewer, the
 * Warlock makes more), and how many more to make once ({@link Order#once}: counted down as they are made). The rooms add
 * their own (RoomWork#gear: the Throne Hall's halberds for its guard, the slings for the slingers the Warlord keeps);
 * the clan keeps the most any of them asks. Also how many slingers the Warlord wants kept armed (Works#keepSlingers).
 * Kept by the Heart's Works; saved with it.
 */
public final class WorkOrders {

    /** Orders at most (kinds of thing). */
    public static final int MAX = 200;
    /** The most of one thing an order keeps or makes. */
    public static final int MOST = 9999;
    /** Slingers kept armed, at most. */
    public static final int SLINGERS_MAX = 200;
    /** Weapon teams kept, at most (the Workshops crew fewer: WarrenHeartBlockEntity#teamRoom). */
    public static final int TEAMS_MAX = 100;

    /** One kind of thing ordered. */
    public static final class Order {
        public final ItemStack item;
        public int keep, once;

        Order(ItemStack item) {
            this.item = item.copyWithCount(1);
        }
    }

    private final Map<Crafter.Key, Order> orders = new LinkedHashMap<>();
    private int slingers;
    private int teams;

    public List<Order> all() {
        return new ArrayList<>(orders.values());
    }

    public Order get(ItemStack like) {
        return orders.get(Crafter.Key.of(like));
    }

    /** Set the order for {@code like}: keep so many, and so many more once (both 0: no order). False if there are too many. */
    public boolean set(ItemStack like, int keep, int once) {
        Crafter.Key k = Crafter.Key.of(like);
        keep = Math.max(0, Math.min(MOST, keep));
        once = Math.max(0, Math.min(MOST, once));
        if (keep == 0 && once == 0) {
            orders.remove(k);
            return true;
        }
        Order o = orders.get(k);
        if (o == null) {
            if (orders.size() >= MAX) {
                return false;
            }
            o = new Order(like);
            orders.put(k, o);
        }
        o.keep = keep;
        o.once = once;
        return true;
    }

    /** {@code n} of it made: counted off its once-order. */
    public void made(ItemStack like, int n) {
        Order o = orders.get(Crafter.Key.of(like));
        if (o != null && o.once > 0) {
            o.once = Math.max(0, o.once - n);
            if (o.once == 0 && o.keep == 0) {
                orders.remove(Crafter.Key.of(like));
            }
        }
    }

    public int slingers() {
        return slingers;
    }

    public void setSlingers(int n) {
        slingers = Math.max(0, Math.min(SLINGERS_MAX, n));
    }

    /** How many Warpfire Thrower teams the Warlord keeps (Works#keepTeams). */
    public int teams() {
        return teams;
    }

    public void setTeams(int n) {
        teams = Math.max(0, Math.min(TEAMS_MAX, n));
    }

    // ------------------------------------------------------------------ for the Hoard-Ledger's Orders page

    /** Where an order stands. */
    public static final int STOCKED = 0, MAKING = 1, SHORT = 2, WAITING = 3;

    /**
     * An order as the Ledger shows it: the thing, the Warlord's keep and once, the rooms' keep and why, how many the
     * clan holds, where it stands and a word on it ("short of 2 Iron Ingot").
     */
    public record Line(ItemStack item, int keep, int roomKeep, int once, int have, int state, String note, String why) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Line> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public Line decode(RegistryFriendlyByteBuf b) {
                return new Line(ItemStack.STREAM_CODEC.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                        ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                        ByteBufCodecs.STRING_UTF8.decode(b), ByteBufCodecs.STRING_UTF8.decode(b));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf b, Line l) {
                ItemStack.STREAM_CODEC.encode(b, l.item());
                ByteBufCodecs.VAR_INT.encode(b, l.keep());
                ByteBufCodecs.VAR_INT.encode(b, l.roomKeep());
                ByteBufCodecs.VAR_INT.encode(b, l.once());
                ByteBufCodecs.VAR_INT.encode(b, l.have());
                ByteBufCodecs.VAR_INT.encode(b, l.state());
                ByteBufCodecs.STRING_UTF8.encode(b, l.note());
                ByteBufCodecs.STRING_UTF8.encode(b, l.why());
            }
        };

        /** How many the clan should keep: the most the Warlord or a room asks. */
        public int keeps() {
            return Math.max(keep, roomKeep);
        }
    }

    /**
     * The whole of it for the Ledger: every order, the slingers (armed, wanted), the weapon teams (kept, wanted, and why
     * no more are raised, or ""), and the Warlock's state ("forging a Stormvermin Halberd", "no Warlock's Workshop").
     */
    public record View(List<Line> lines, int slingers, int slingersWanted, int teams, int teamsWanted, String teamsNote, String warlock) {
        public static final View NONE = new View(List.of(), 0, 0, 0, 0, "", "");
        private static final StreamCodec<RegistryFriendlyByteBuf, List<Line>> LINES = Line.STREAM_CODEC.apply(ByteBufCodecs.list());
        public static final StreamCodec<RegistryFriendlyByteBuf, View> STREAM_CODEC = new StreamCodec<>() {
            @Override
            public View decode(RegistryFriendlyByteBuf b) {
                return new View(LINES.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b),
                        ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.STRING_UTF8.decode(b), ByteBufCodecs.STRING_UTF8.decode(b));
            }

            @Override
            public void encode(RegistryFriendlyByteBuf b, View v) {
                LINES.encode(b, v.lines());
                ByteBufCodecs.VAR_INT.encode(b, v.slingers());
                ByteBufCodecs.VAR_INT.encode(b, v.slingersWanted());
                ByteBufCodecs.VAR_INT.encode(b, v.teams());
                ByteBufCodecs.VAR_INT.encode(b, v.teamsWanted());
                ByteBufCodecs.STRING_UTF8.encode(b, v.teamsNote());
                ByteBufCodecs.STRING_UTF8.encode(b, v.warlock());
            }
        };

        /** The same, with the slingers wanted changed (the Ledger's own at once, before the server's word). */
        public View withSlingersWanted(int n) {
            return new View(lines, slingers, n, teams, teamsWanted, teamsNote, warlock);
        }

        public View withTeamsWanted(int n) {
            return new View(lines, slingers, slingersWanted, teams, n, teamsNote, warlock);
        }

        public View withLines(List<Line> l) {
            return new View(l, slingers, slingersWanted, teams, teamsWanted, teamsNote, warlock);
        }
    }

    // ------------------------------------------------------------------ save / load

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag t = new CompoundTag();
        ListTag l = new ListTag();
        for (Order o : orders.values()) {
            CompoundTag c = new CompoundTag();
            c.put("item", o.item.save(registries));
            c.putInt("keep", o.keep);
            c.putInt("once", o.once);
            l.add(c);
        }
        t.put("orders", l);
        t.putInt("slingers", slingers);
        t.putInt("teams", teams);
        return t;
    }

    public void load(CompoundTag t, HolderLookup.Provider registries) {
        orders.clear();
        for (Tag x : t.getListOrEmpty("orders")) {
            CompoundTag c = (CompoundTag) x;
            ItemStack.parse(registries, c.getCompound("item")).ifPresent(s -> set(s, c.getInt("keep"), c.getInt("once")));
        }
        slingers = t.getInt("slingers");
        teams = t.getInt("teams");
    }
}
