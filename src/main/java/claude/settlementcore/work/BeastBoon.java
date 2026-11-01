package claude.settlementcore.work;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.item.ItemStack;

/**
 * A race's way of feeding its beasts to make them richer (Under-Empire: warp-feeding, clan/WarpBeasts): a fed beast gives a
 * double cut, whelps faster and bears young already fed; it needs feeding again every {@link #upkeep} ticks or the boon
 * fades. The first feeding can go wrong. The Beast Pens' crew does the feeding (core/work/PensWork) with what the boon
 * {@link #feeds on} from the Fodder Bin. Registered with {@link PensWork#boon(BeastBoon)}; without one, beasts are just beasts.
 */
public interface BeastBoon {

    /** What a feeding did. */
    enum Outcome { TOUCHED, SICKENED, FERAL }

    /** No boon: nothing is ever fed. */
    BeastBoon NONE = new BeastBoon() {
        @Override
        public boolean feeds(ItemStack s) {
            return false;
        }

        @Override
        public boolean touched(Animal a) {
            return false;
        }

        @Override
        public void touch(Animal a, long now) {
        }

        @Override
        public void fade(Animal a) {
        }

        @Override
        public long fedAt(Animal a) {
            return 0;
        }

        @Override
        public long upkeep() {
            return Long.MAX_VALUE / 4;
        }

        @Override
        public Outcome feed(ServerLevel level, Animal a, boolean sure) {
            return Outcome.TOUCHED;
        }

        @Override
        public void burst(ServerLevel level, Animal a, int n) {
        }

        @Override
        public ItemStack mark(ItemStack meat) {
            return meat;
        }

        @Override
        public String choreName() {
            return "feeding";
        }

        @Override
        public Component sickened(String beast) {
            return Component.empty();
        }

        @Override
        public Component feral(String beast) {
            return Component.empty();
        }
    };

    /** What it feeds the beasts (a warpstone shard). */
    boolean feeds(ItemStack s);

    boolean touched(Animal a);

    /** Fed now (or fed again). */
    void touch(Animal a, long now);

    /** The boon fades: a plain beast again. */
    void fade(Animal a);

    /** Game time it was last fed (0: never). */
    long fedAt(Animal a);

    /** Ticks one feeding lasts. */
    long upkeep();

    /**
     * One feeding: fed, or it sickened and died, or it turned feral. {@code sure}: the first feeding cannot go wrong
     * (tests only, {@link PensWork#sureFeedingForTests}; in play it is always false and the boon's own odds decide).
     */
    Outcome feed(ServerLevel level, Animal a, boolean sure);

    /** A puff of the boon's colour about a beast. */
    void burst(ServerLevel level, Animal a, int n);

    /** Meat cut from a fed beast. */
    ItemStack mark(ItemStack meat);

    /** The crew's name for the feeding ("warp-feeding"). */
    String choreName();

    /** Told to the owner when a beast sickens on the first feeding. */
    Component sickened(String beast);

    /** Told to the owner when a beast turns feral. */
    Component feral(String beast);

    /** One of its beasts gone feral on the first feeding (Under-Empire: a pig turned Zoglin), for the crew to put down. */
    default boolean wentFeral(net.minecraft.world.entity.Entity e) {
        return false;
    }

    /** Told to the owner when the crew has put a feral beast down. */
    default Component putDown(String beast) {
        return Component.literal("The Beast Pens' crew put the feral " + beast + " down.").withStyle(net.minecraft.ChatFormatting.GRAY);
    }
}
