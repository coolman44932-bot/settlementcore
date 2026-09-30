package claude.underempire.core.ledger;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the clan's goods and food go (user, 2026-09-26: "add a way to easily track where materials go and where food
 * goes"). Every road the horde carries things along (nest/work/Works#haul: a room's work to the Hoard, the larder, the
 * next room; the Hoard's supply out to the rooms), what the Warlord takes from the Ledger or puts in, the Heart's
 * warpstone in and out, and the larder's food: what fed it and what ate it. Kept by the Heart for the last
 * {@link #MINUTES} minutes, a minute to a bucket, and never saved. The Hoard-Ledger's Flows page and the far-peeker read it.
 */
public final class HaulLog {

    public static final int MINUTES = 10;
    private static final long MINUTE = 1200;

    /** The places that are not rooms. */
    public static final String LARDER = "Larder", WARPSTONE = "Heart's warpstone", SPOILS = "Heart's spoils", WARLORD = "You",
            EATEN = "Eaten by the clan";

    /**
     * One road: from where, to where, what, and how much went along it. {@code item} null is the larder's food itself,
     * counted in food (the larder's side of a feeding, or what the clan ate).
     */
    public record Flow(String from, String to, @Nullable Item item, long count) {

        public static final StreamCodec<ByteBuf, Flow> STREAM_CODEC = StreamCodec.of((buf, f) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, f.from());
            ByteBufCodecs.STRING_UTF8.encode(buf, f.to());
            ByteBufCodecs.STRING_UTF8.encode(buf, f.item() == null ? "" : BuiltInRegistries.ITEM.getKey(f.item()).toString());
            ByteBufCodecs.VAR_LONG.encode(buf, f.count());
        }, buf -> {
            String from = ByteBufCodecs.STRING_UTF8.decode(buf), to = ByteBufCodecs.STRING_UTF8.decode(buf);
            String id = ByteBufCodecs.STRING_UTF8.decode(buf);
            Item item = id.isEmpty() ? null : BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            return new Flow(from, to, item == Items.AIR ? null : item, ByteBufCodecs.VAR_LONG.decode(buf));
        });

        /** Food, not goods: the larder's side. */
        public boolean food() {
            return item == null;
        }
    }

    private record Key(String from, String to, @Nullable Item item) {
    }

    private record Bucket(long minute, Map<Key, long[]> counts) {
    }

    private final ArrayDeque<Bucket> buckets = new ArrayDeque<>();

    /** {@code n} of {@code item} (null: food) went from {@code from} to {@code to} at game time {@code now}. */
    public void add(String from, String to, @Nullable Item item, long n, long now) {
        if (n <= 0 || from.equals(to)) {
            return;
        }
        long minute = now / MINUTE;
        Bucket b = buckets.peekLast();
        if (b == null || b.minute() != minute) {
            b = new Bucket(minute, new HashMap<>());
            buckets.addLast(b);
        }
        b.counts().computeIfAbsent(new Key(from, to, item), k -> new long[1])[0] += n;
        while (!buckets.isEmpty() && buckets.peekFirst().minute() <= minute - MINUTES) {
            buckets.pollFirst();
        }
    }

    /** Every road of the last {@link #MINUTES} minutes, the busiest first. */
    public List<Flow> flows(long now) {
        long first = now / MINUTE - MINUTES;
        Map<Key, Long> sum = new HashMap<>();
        for (Bucket b : buckets) {
            if (b.minute() > first) {
                b.counts().forEach((k, v) -> sum.merge(k, v[0], Long::sum));
            }
        }
        List<Flow> out = new ArrayList<>();
        sum.forEach((k, v) -> out.add(new Flow(k.from(), k.to(), k.item(), v)));
        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }

    /** The larder's food over the last {@link #MINUTES} minutes: {in, out}. */
    public static long[] larder(List<Flow> flows) {
        long in = 0, out = 0;
        for (Flow f : flows) {
            if (f.food() && f.to().equals(LARDER)) {
                in += f.count();
            } else if (f.food() && f.from().equals(LARDER)) {
                out += f.count();
            }
        }
        return new long[]{in, out};
    }

    /** What came into ({@code into}) or went out of {@code place}, goods only: item and count, the most first. */
    public static List<Flow> at(List<Flow> flows, String place, boolean into) {
        List<Flow> out = new ArrayList<>();
        for (Flow f : flows) {
            if (!f.food() && (into ? f.to() : f.from()).equals(place)) {
                out.add(f);
            }
        }
        return out;
    }
}
