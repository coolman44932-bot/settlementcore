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
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Beast Pens: Clan Moulder's stock. The pens come with two of each beast (cows, sheep, pigs,
 * chickens), one kind a paddock. The crew keeps them from outside the fences (a Skaven's reach and a
 * hook-pole): feeds a pair from the Fodder Bin to breed while a kind is under {@link #MAX_KIND},
 * shears sheep, butchers grown beasts over {@link #KEEP_ADULTS} of a kind (killed where they stand, so
 * the loot is a beast's own loot), and gathers up what lies on the paddock floors (eggs, wool, meat,
 * leather, feathers) into the Butcher's Bin. The Slave Fields keep the Fodder Bin full (Works#haul);
 * the horde takes the Butcher's Bin to the larder and the Hoard.
 *
 * <p>The pens give without breeding or killing too (user, 2026-09-25: "make it so it can produce more animals
 * without breeding and it can produce food without killing mobs, killing and breeding just make it more
 * faster"). Each paddock whelps a young one every {@link #WHELP_SECONDS} while it is under its cap, fodder or
 * none, and one is brought in if the kind is gone; and the crew carves a cut of meat from each grown beast every
 * {@link #CARVE_TICKS}, Clan Moulder's way, the warp-touched flesh knitting back (the beast is not harmed).
 * Feeding pairs and butchering the surplus are the faster ways.
 *
 * <p>A beast gone feral on the first shard (a pig turned Zoglin, BeastBoon#wentFeral) is put down over the fence with the
 * hook-pole before anything else, its drops into the Butcher's Bin, and the crew never take it for a foe to fight
 * ({@link #putsDown}): set on it by the warren's alarm, they stood at the fence for good (2026-09-27).
 */
public class PensWork extends RoomWork {

    /** How the race feeds its beasts to make them richer (Under-Empire: warp-feeding); none by default. */
    private static volatile BeastBoon boon = BeastBoon.NONE;
    /** Animals that are never livestock (Under-Empire: its rats, vermin not meat). */
    private static final java.util.List<java.util.function.Predicate<Animal>> NOT_LIVESTOCK = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The race's boon for its beasts (as the mod starts). */
    public static void boon(BeastBoon b) {
        boon = b;
    }

    public static BeastBoon boon() {
        return boon;
    }

    /** An animal that is never livestock, however it got into the pens. */
    public static void notLivestock(java.util.function.Predicate<Animal> which) {
        NOT_LIVESTOCK.add(which);
    }

    static boolean livestock(Animal a) {
        for (var p : NOT_LIVESTOCK) {
            if (p.test(a)) {
                return false;
            }
        }
        return true;
    }

    public static final String BUTCHER_BIN = "Butcher's Bin", FODDER_BIN = "Fodder Bin";
    static final int MAX_KIND = 8;
    static final int KEEP_ADULTS = 4;
    /** Reach over a fence into a paddock, with a hook-pole: the whole paddock from the aisle, its back troughs too. */
    static final double POLE = 7.0;
    static final Map<String, EntityType<? extends Animal>> STOCK = Map.of(
            "pen_cow", EntityType.COW, "pen_sheep", EntityType.SHEEP, "pen_pig", EntityType.PIG, "pen_chicken", EntityType.CHICKEN);

    List<BlockPos> butcher = List.of();
    List<BlockPos> fodder = List.of();
    boolean stocked;
    long born, butchered, milked, whelped, carved, cuts;
    /** Warp-feeding (clan/WarpBeasts): beasts touched by a first shard, shards to keep them so, and the ones it went wrong for. */
    long warped, renewed, sickened, feral;
    /** Feral beasts the crew put down. */
    long putDown;
    /**
     * Tests only, never set in play (and not saved): the first feeding here never goes wrong (BeastBoon#feed's sure), so
     * a test of the crew is not also a test of the boon's gamble. aPennedCrewRatGnawsItsWayOut failed once when a pig
     * turned feral (2026-09-27).
     */
    public boolean sureFeedingForTests;
    /** A paddock whelps a young one this often (seconds) while under its cap, bred or not. */
    public static final int WHELP_SECONDS = 180;
    /** A grown beast gives a cut of meat to the carvers this often (ticks), and lives. */
    public static final int CARVE_TICKS = 4800;
    /** When each paddock last whelped, and each beast was last carved. */
    private final Map<String, Long> whelpedAt = new HashMap<>();
    private final Map<java.util.UUID, Long> carvedAt = new HashMap<>();

    /** The cut the carvers take from a living beast of this kind, or empty if it gives none. */
    static ItemStack cut(Animal a) {
        EntityType<?> t = a.getType();
        return t == EntityType.COW || t == EntityType.MOOSHROOM ? new ItemStack(Items.BEEF)
                : t == EntityType.PIG ? new ItemStack(Items.PORKCHOP)
                : t == EntityType.SHEEP ? new ItemStack(Items.MUTTON)
                : t == EntityType.CHICKEN ? new ItemStack(Items.CHICKEN)
                : t == EntityType.RABBIT ? new ItemStack(Items.RABBIT) : ItemStack.EMPTY;
    }

    /** Carve a cut from a living beast into the Butcher's Bin (or at its feet, gathered up after). */
    boolean carve(ServerLevel level, Animal a) {
        ItemStack meat = cut(a);
        if (meat.isEmpty() || !a.isAlive() || a.isBaby()) {
            return false;
        }
        carvedAt.put(a.getUUID(), level.getGameTime());
        if (boon().touched(a)) {
            boon().mark(meat);
            meat.setCount(2);                                    // warp-touched flesh: twice the cut
        }
        cuts += meat.getCount();
        ItemStack rest = store(level, butcher, meat);
        if (!rest.isEmpty()) {
            a.spawnAtLocation(rest);
        }
        level.getChunkSource().broadcastAndSend(a, new net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket(a));
        level.playSound(null, a.blockPosition(), net.minecraft.sounds.SoundEvents.SHEEP_SHEAR, SoundSource.NEUTRAL, 0.8f, 0.7f);
        level.sendParticles(new net.minecraft.core.particles.DustParticleOptions(new org.joml.Vector3f(0.35f, 0.95f, 0.25f), 1f),
                a.getX(), a.getY() + a.getBbHeight() * 0.6, a.getZ(), 6, 0.25, 0.2, 0.25, 0.02);   // warp-green: it knits back
        carved++;
        return true;
    }

    /** A young one for a paddock: born of the herd, or brought in if the kind is gone. */
    @Nullable
    Animal whelp(ServerLevel level, EntityType<? extends Animal> type, BlockPos at) {
        return whelp(level, type, at, false);
    }

    @Nullable
    Animal whelp(ServerLevel level, EntityType<? extends Animal> type, BlockPos at, boolean warped) {
        Animal a = type.create(level);
        if (a == null) {
            return null;
        }
        a.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, level.random.nextFloat() * 360f, 0);
        a.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.BREEDING, null);
        a.setBaby(true);
        a.setPersistenceRequired();
        if (warped) {
            boon().touch(a, level.getGameTime());
        }
        level.addFreshEntity(a);
        whelped++;
        heart.tally(Settlement.Deed.BRED, 1);
        return a;
    }

    /** Beasts touched by a first shard, shards that kept them so, and the ones that sickened or turned feral. */
    public long warped() {
        return warped;
    }

    public long renewed() {
        return renewed;
    }

    public long sickened() {
        return sickened;
    }

    public long feral() {
        return feral;
    }

    /** Feral beasts the crew put down. */
    public long putDown() {
        return putDown;
    }

    /** Beasts gone feral in the pens, for the crew to put down. */
    public List<net.minecraft.world.entity.Mob> ferals(ServerLevel level) {
        return level.getEntitiesOfClass(net.minecraft.world.entity.Mob.class, AABB.of(box()), m -> m.isAlive() && boon().wentFeral(m));
    }

    /** The crew's chore, not a fight: they never set on a feral beast in the pens (SettlerMob#canAttack). */
    @Override
    public boolean putsDown(net.minecraft.world.entity.LivingEntity foe) {
        return boon().wentFeral(foe) && box().isInside(foe.blockPosition());
    }

    /**
     * Put a feral beast down where it stands (the hook-pole, over the fence), by {@code by} of the crew or, in the night's
     * work, by nobody in particular. What it drops goes into the Butcher's Bin.
     */
    boolean putDown(ServerLevel level, net.minecraft.world.entity.Mob beast, @Nullable SettlerMob by) {
        if (!beast.isAlive()) {
            return false;
        }
        beast.hurt(by == null ? level.damageSources().generic() : level.damageSources().mobAttack(by), Float.MAX_VALUE);
        if (beast.isAlive()) {
            return false;
        }
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, beast.getBoundingBox().inflate(1.5), e -> e.isAlive() && e.getAge() <= 1)) {
            ItemStack rest = store(level, butcher, e.getItem().copy());
            if (rest.isEmpty()) {
                e.discard();
            } else {
                e.setItem(rest);
            }
        }
        putDown++;
        tell(boon().putDown(beast.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT)));
        return true;
    }

    /** Word to the Warlord, if he is about. */
    private void tell(Component msg) {
        var p = heart.ownerPlayer();
        if (p != null) {
            p.sendSystemMessage(msg);
        }
    }

    /** How many young the paddocks have whelped by themselves, and how many cuts the carvers took. */
    public long whelped() {
        return whelped;
    }

    public long carved() {
        return carved;
    }

    /** Pieces of meat the carvers took (two a cut from a warp-touched beast). */
    public long cuts() {
        return cuts;
    }
    /** When each cow was last milked. */
    private final Map<java.util.UUID, Long> milkedAt = new HashMap<>();
    /** A cow gives milk once a minute. */
    static final int MILK_TICKS = 1200;

    static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> MILK = net.minecraft.tags.TagKey.create(
            net.minecraft.core.registries.Registries.ITEM, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("c", "milk"));

    /**
     * What a cow is milked into: Pam's HarvestCraft's fresh milk (the c:milk that is not a bucket of it: the
     * Cook-Pits make its butter, cheese and cream), or, without it, a bucket from the Fodder Bin. Null if
     * there is neither.
     */
    @Nullable
    ItemStack milk(ServerLevel level) {
        for (var h : net.minecraft.core.registries.BuiltInRegistries.ITEM.getTagOrEmpty(MILK)) {
            if (new ItemStack(h.value()).getCraftingRemainingItem().isEmpty()) {
                return new ItemStack(h.value(), 2);
            }
        }
        return Bins.count(level, fodder, st -> st.is(Items.BUCKET)) > 0 ? new ItemStack(Items.MILK_BUCKET) : null;
    }

    /** How much milk the pens have given. */
    public long milked() {
        return milked;
    }

    /** Milk a cow into the Butcher's Bin (a bucket taken from the Fodder Bin for vanilla's milk). */
    boolean milkCow(ServerLevel level, net.minecraft.world.entity.animal.Cow cow) {
        ItemStack m = milk(level);
        if (m == null || !Bins.hasRoom(level, butcher, m)) {
            return false;
        }
        if (m.is(Items.MILK_BUCKET) && Bins.take(level, fodder, st -> st.is(Items.BUCKET), 1).isEmpty()) {
            return false;
        }
        milkedAt.put(cow.getUUID(), level.getGameTime());
        ItemStack rest = store(level, butcher, m);
        if (!rest.isEmpty()) {
            cow.spawnAtLocation(rest);
        }
        level.playSound(null, cow.blockPosition(), net.minecraft.sounds.SoundEvents.COW_MILK, SoundSource.NEUTRAL, 1f, 1f);
        milked += m.getCount();
        return true;
    }

    public PensWork(Settlement heart, Building building, PlanPlacement placement) {
        super(heart, building, placement);
    }

    @Override
    protected void scan(ServerLevel level) {
        butcher = Bins.named(level, box(), BUTCHER_BIN);
        fodder = Bins.named(level, box(), FODDER_BIN);
    }

    public List<BlockPos> butcherBins(ServerLevel level) {
        rescan(level);
        return butcher;
    }

    public List<BlockPos> fodderBins(ServerLevel level) {
        rescan(level);
        return fodder;
    }

    @Override
    public int crewWanted() {
        return 2;
    }

    /** The pens take in their stock: two of each kind, in its paddock. */
    @Override
    public void opened(ServerLevel level) {
        if (stocked) {
            return;
        }
        for (Spot sp : stationsAll()) {
            EntityType<? extends Animal> type = STOCK.get(role(sp));
            if (type == null) {
                continue;
            }
            for (int i = 0; i < 2; i++) {
                Animal a = type.create(level);
                if (a == null) {
                    continue;
                }
                a.moveTo(sp.pos().getX() + 0.5 + (i - 0.5), sp.pos().getY(), sp.pos().getZ() + 0.5, level.random.nextFloat() * 360f, 0);
                a.finalizeSpawn(level, level.getCurrentDifficultyAt(sp.pos()), MobSpawnType.STRUCTURE, null);
                a.setPersistenceRequired();
                level.addFreshEntity(a);
            }
        }
        stocked = true;
    }

    private final Map<Spot, String> roles = new HashMap<>();

    private List<Spot> stationsAll() {
        List<Spot> out = new ArrayList<>();
        for (String role : STOCK.keySet()) {
            for (Spot s : stations(role)) {
                roles.put(s, role);
                out.add(s);
            }
        }
        return out;
    }

    private String role(Spot s) {
        return roles.getOrDefault(s, "");
    }

    /** Every beast in the pens (any the Warlord drove in too), but not a rat that wandered in (entity/Rat: vermin, not meat). */
    public List<Animal> beasts(ServerLevel level) {
        return level.getEntitiesOfClass(Animal.class, AABB.of(box()), a -> a.isAlive() && livestock(a));
    }

    /** Every second: a paddock under its cap whelps when its time comes (no crew or fodder needed). */
    @Override
    public void second(ServerLevel level, int crewHere) {
        rescan(level);
        long now = level.getGameTime();
        List<Animal> all = beasts(level);
        for (Animal a : all) {
            if (boon().touched(a)) {
                if (now - boon().fedAt(a) >= boon().upkeep() + 1200) {
                    boon().fade(a);   // no shard to keep it: the warp fades
                    boon().burst(level, a, 6);
                } else if (level.random.nextInt(3) == 0) {
                    boon().burst(level, a, 1);
                }
            }
        }
        for (Spot sp : stationsAll()) {
            String role = role(sp);
            EntityType<? extends Animal> type = STOCK.get(role);
            if (type == null) {
                continue;
            }
            List<Animal> kind = all.stream().filter(a -> a.getType() == type).toList();
            long adults = kind.stream().filter(a -> !a.isBaby()).count();
            long warpedAdults = kind.stream().filter(a -> !a.isBaby() && boon().touched(a)).count();
            long every = WHELP_SECONDS * 20L / (warpedAdults > 0 && warpedAdults * 2 >= adults ? 2 : 1);
            long since = whelpedAt.computeIfAbsent(role, k -> now);
            if (now - since < every) {
                continue;
            }
            whelpedAt.put(role, now);
            if (kind.size() < MAX_KIND && level.isLoaded(sp.pos())) {
                whelp(level, type, sp.pos(), warpedAdults > 0);
            }
        }
    }

    @Override
    @Nullable
    public Chore nextChore(ServerLevel level, SettlerMob rat) {
        rescan(level);
        long now = level.getGameTime();
        // a beast gone feral first of all: put down over the fence with the hook-pole
        for (var m : ferals(level)) {
            if (free(m.getUUID(), now)) {
                return new Chore("putting down", m.blockPosition(), POLE, 12, m.getUUID(), m, (l, r) -> {
                    putDown(l, m, r);
                    return null;
                });
            }
        }
        List<Animal> all = beasts(level);
        Map<EntityType<?>, List<Animal>> kinds = new HashMap<>();
        for (Animal a : all) {
            kinds.computeIfAbsent(a.getType(), k -> new ArrayList<>()).add(a);
        }
        // what lies on the paddock floors, into the Butcher's Bin
        List<ItemEntity> lying = level.getEntitiesOfClass(ItemEntity.class, AABB.of(box()), e -> e.isAlive() && produce(e.getItem()));
        if (!lying.isEmpty() && free("gather", now) && Bins.hasRoom(level, butcher, lying.get(0).getItem())) {
            ItemEntity first = lying.get(0);
            return new Chore("gathering up", first.blockPosition(), POLE, 8, "gather", (l, r) -> {
                for (ItemEntity e : l.getEntitiesOfClass(ItemEntity.class, AABB.of(box()), e -> e.isAlive() && produce(e.getItem()))) {
                    if (e.distanceToSqr(r) > POLE * POLE * 1.5) {
                        continue;
                    }
                    ItemStack rest = store(l, butcher, e.getItem().copy());
                    if (rest.isEmpty()) {
                        e.discard();
                    } else {
                        e.setItem(rest);
                    }
                }
                return null;
            });
        }
        for (var e : kinds.entrySet()) {
            List<Animal> herd = e.getValue();
            List<Animal> adults = herd.stream().filter(a -> !a.isBaby()).sorted(Comparator.comparingDouble(a -> a.distanceToSqr(rat))).toList();
            // shear
            for (Animal a : adults) {
                if (a instanceof Sheep sh && sh.readyForShearing() && free(a.getUUID(), now)) {
                    return new Chore("shearing", a.blockPosition(), POLE, 12, a.getUUID(), a, (l, r) -> {
                        if (sh.isAlive() && sh.readyForShearing()) {
                            sh.shear(SoundSource.NEUTRAL);               // the wool falls on the floor; gathered up after
                        }
                        return null;
                    });
                }
            }
            // butcher the surplus: the nearest grown beast that is free and not in love (before the milking: a
            // cow about to be killed is not milked, and the milking must not keep the surplus alive)
            Object butchering = "butchering " + e.getKey();       // one of a kind at a time: two rats would kill two
            if (adults.size() > KEEP_ADULTS && free(butchering, now)) {
                for (Animal a : adults) {
                    if (a.isInLove() || !free(a.getUUID(), now)) {
                        continue;
                    }
                    return new Chore("butchering", a.blockPosition(), POLE, 16, butchering, a, (l, r) -> {
                        if (a.isAlive() && !a.isBaby()) {
                            a.hurt(l.damageSources().mobAttack(r), Float.MAX_VALUE);
                            if (!a.isAlive()) {
                                butchered++;
                            }
                        }
                        return null;
                    });
                }
            }
            // milk the cows
            for (Animal a : adults) {
                if (a instanceof net.minecraft.world.entity.animal.Cow cow && now - milkedAt.getOrDefault(a.getUUID(), Long.MIN_VALUE / 2) >= MILK_TICKS
                        && free(a.getUUID(), now) && milk(level) != null) {
                    return new Chore("milking", a.blockPosition(), POLE, 14, a.getUUID(), a, (l, r) -> {
                        if (cow.isAlive() && !cow.isBaby()) {
                            milkCow(l, cow);
                        }
                        return null;
                    });
                }
            }
            // warp-feeding: a shard from the Fodder Bin, to keep a warp-touched beast so, or to touch a plain one
            if (Bins.count(level, fodder, st -> boon().feeds(st)) > 0) {
                Animal pick = null;
                for (Animal a : adults) {
                    boolean touched = boon().touched(a);
                    long fed = boon().fedAt(a);
                    if (a.isInLove() || !free(a.getUUID(), now) || touched && now - fed < boon().upkeep() - 2400) {
                        continue;
                    }
                    if (pick == null || touched && !boon().touched(pick)) {
                        pick = a;                                // keeping a touched beast so comes before touching a new one
                    }
                }
                if (pick != null) {
                    Animal a = pick;
                    return new Chore(boon().choreName(), a.blockPosition(), POLE, 12, a.getUUID(), a, (l, r) -> {
                        if (a.isAlive() && !a.isBaby()
                                && !Bins.take(l, fodder, st -> boon().feeds(st), 1).isEmpty()) {
                            boolean renew = boon().touched(a);
                            String name = a.getType().getDescription().getString().toLowerCase(java.util.Locale.ROOT);
                            switch (boon().feed(l, a, sureFeedingForTests)) {
                                case TOUCHED -> {
                                    if (renew) {
                                        renewed++;
                                    } else {
                                        warped++;
                                    }
                                }
                                case SICKENED -> {
                                    sickened++;
                                    tell(boon().sickened(name));
                                }
                                case FERAL -> {
                                    feral++;
                                    tell(boon().feral(name));
                                }
                            }
                        }
                        return null;
                    });
                }
            }
            // carve a cut from a living beast (it is not harmed: the warp-touched flesh knits back)
            for (Animal a : adults) {
                if (!cut(a).isEmpty() && now - carvedAt.getOrDefault(a.getUUID(), Long.MIN_VALUE / 2) >= CARVE_TICKS
                        && !a.isInLove() && free(a.getUUID(), now) && Bins.hasRoom(level, butcher, cut(a))) {
                    return new Chore("carving", a.blockPosition(), POLE, 12, a.getUUID(), a, (l, r) -> {
                        carve(l, a);
                        return null;
                    });
                }
            }
            // breed a pair
            if (herd.size() < MAX_KIND) {
                List<Animal> ready = adults.stream().filter(a -> a.canFallInLove() && a.getAge() == 0).toList();
                if (ready.size() >= 2) {
                    Animal a = ready.get(0);
                    ItemStack food = Bins.peek(level, fodder, a::isFood);
                    if (!food.isEmpty() && Bins.count(level, fodder, a::isFood) >= 2 && free(e.getKey(), now)) {
                        Animal b = ready.get(1);
                        return new Chore("feeding the beasts", a.blockPosition(), POLE, 10, e.getKey(), a, (l, r) -> {
                            int fed = 0;
                            for (Animal x : List.of(a, b)) {
                                if (x.isAlive() && x.canFallInLove() && x.getAge() == 0 && x.distanceToSqr(r) < POLE * POLE * 2
                                        && !Bins.take(l, fodder, x::isFood, 1).isEmpty()) {
                                    x.setInLove(null);
                                    fed++;
                                }
                            }
                            if (fed == 2) {
                                born++;
                                heart.tally(Settlement.Deed.BRED, 1);
                            }
                            return null;
                        });
                    }
                }
            }
        }
        return null;
    }

    /** What a beast gives: meat, eggs, wool, leather, feathers, hides. */
    static boolean produce(ItemStack s) {
        return s.is(Items.EGG) || s.is(Items.FEATHER) || s.is(Items.LEATHER) || s.is(Items.RABBIT_HIDE) || s.is(net.minecraft.tags.ItemTags.WOOL)
                || s.is(Items.BEEF) || s.is(Items.PORKCHOP) || s.is(Items.MUTTON) || s.is(Items.CHICKEN) || s.is(Items.RABBIT)
                || s.is(Items.COOKED_BEEF) || s.is(Items.COOKED_PORKCHOP) || s.is(Items.COOKED_MUTTON) || s.is(Items.COOKED_CHICKEN);
    }

    /** The night in the pens: pairs bred as the crew would have, the young grown on, the surplus butchered, the floors swept. */
    @Override
    public void nightsWork(ServerLevel level, int seconds, List<Component> report) {
        rescan(level);
        for (var m : ferals(level)) {
            putDown(level, m, null);                             // the crew would have put it down first thing
        }
        long b0 = born, k0 = butchered, w0 = whelped, c0 = carved;
        // the paddocks whelp through the night, bred or not (the young grow on below with the rest)
        for (Spot sp : stationsAll()) {
            EntityType<? extends Animal> type = STOCK.get(role(sp));
            if (type == null || !level.isLoaded(sp.pos())) {
                continue;
            }
            List<Animal> kind = beasts(level).stream().filter(a -> a.getType() == type).toList();
            boolean warpedHerd = kind.stream().anyMatch(a -> !a.isBaby() && boon().touched(a));
            long herd = kind.size();
            for (long i = 0; i < seconds / WHELP_SECONDS * (warpedHerd ? 2 : 1) && herd < MAX_KIND; i++, herd++) {
                whelp(level, type, sp.pos(), warpedHerd);
            }
        }
        List<Animal> all = beasts(level);
        long upkeeps = seconds * 20L / boon().upkeep();
        for (Animal a : all) {
            if (a.isBaby()) {
                a.ageUp(seconds, true);                          // seconds of growing (ageUp takes seconds)
            }
            if (boon().touched(a) && upkeeps > 0) {
                boolean kept = true;
                for (long i = 0; i < upkeeps && kept; i++) {
                    kept = !Bins.take(level, fodder, st -> boon().feeds(st), 1).isEmpty();
                    renewed += kept ? 1 : 0;
                }
                if (kept) {
                    boon().touch(a, level.getGameTime());
                } else {
                    boon().fade(a);
                }
            }
        }
        Map<EntityType<?>, List<Animal>> kinds = new HashMap<>();
        for (Animal a : beasts(level)) {
            kinds.computeIfAbsent(a.getType(), k -> new ArrayList<>()).add(a);
        }
        int rounds = Math.max(1, seconds / 300);                  // a vanilla beast breeds again after 5 minutes
        for (List<Animal> herd : kinds.values()) {
            for (int i = 0; i < rounds; i++) {
                List<Animal> adults = herd.stream().filter(a -> a.isAlive() && !a.isBaby()).toList();
                if (herd.stream().filter(Animal::isAlive).count() >= MAX_KIND || adults.size() < 2) {
                    break;
                }
                Animal a = adults.get(0), b = adults.get(1);
                if (Bins.count(level, fodder, a::isFood) < 2) {
                    break;
                }
                Bins.take(level, fodder, a::isFood, 1);
                Bins.take(level, fodder, b::isFood, 1);
                var baby = a.getBreedOffspring(level, b);
                if (baby instanceof Animal child) {
                    child.setBaby(true);
                    child.moveTo(a.getX(), a.getY(), a.getZ(), 0, 0);
                    child.setPersistenceRequired();
                    level.addFreshEntityWithPassengers(child);
                    herd = new ArrayList<>(herd);
                    herd.add(child);
                    born++;
                    heart.tally(Settlement.Deed.BRED, 1);
                }
            }
            List<Animal> adults = herd.stream().filter(a -> a.isAlive() && !a.isBaby()).toList();
            for (int i = KEEP_ADULTS; i < adults.size(); i++) {
                Animal a = adults.get(i);
                a.hurt(level.damageSources().generic(), Float.MAX_VALUE);
                butchered++;
            }
            for (Animal a : adults) {
                if (a instanceof Sheep sh && sh.readyForShearing()) {
                    sh.shear(SoundSource.NEUTRAL);
                }
                if (a instanceof net.minecraft.world.entity.animal.Cow cow && cow.isAlive()) {
                    for (int i = 0; i < Math.min(8, seconds * 20 / MILK_TICKS); i++) {
                        if (!milkCow(level, cow)) {
                            break;
                        }
                    }
                }
                for (int i = 0; a.isAlive() && i < Math.min(6, seconds * 20 / CARVE_TICKS); i++) {
                    if (!Bins.hasRoom(level, butcher, cut(a)) || !carve(level, a)) {
                        break;
                    }
                }
            }
        }
        if (born > b0 || butchered > k0 || whelped > w0 || carved > c0) {
            report.add(Component.literal("The pens: " + (born - b0 + whelped - w0) + " young born, " + (carved - c0) + " cuts carved, "
                    + (butchered - k0) + " beasts butchered.").withStyle(ChatFormatting.DARK_GREEN));
        }
    }

    /** After the night, what the pens left on their floors (drops, wool, eggs) is gathered up. */
    public void sweep(ServerLevel level) {
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, AABB.of(box()), e -> e.isAlive() && produce(e.getItem()))) {
            ItemStack rest = store(level, butcher, e.getItem().copy());
            if (rest.isEmpty()) {
                e.discard();
            } else {
                e.setItem(rest);
            }
        }
    }

    /** Fodder the beasts in the pens eat, in the Fodder Bin: fed pairs breed. */
    @Override
    public List<Supply> supplies(ServerLevel level) {
        List<Animal> all = beasts(level);
        return List.of(new Supply("fodder", fodder, s -> {
            for (Animal a : all) {
                if (a.isFood(s)) {
                    return true;
                }
            }
            return false;
        }, Works.FODDER_KEEP));
    }

    @Override
    public void wants(ServerLevel level, List<String> needs, List<String> boosts) {
        List<Animal> all = beasts(level);
        if (!all.isEmpty() && all.stream().noneMatch(a -> Bins.count(level, fodder, a::isFood) >= 2)) {
            boosts.add("fodder in the Fodder Bin: fed pairs breed");
        }
        roomIn(level, butcher, BUTCHER_BIN, needs);
    }

    @Override
    public void describe(ServerLevel level, List<Component> out) {
        Map<String, Integer> n = new HashMap<>();
        for (Animal a : beasts(level)) {
            n.merge(a.getType().getDescription().getString(), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("Beast Pens: ");
        n.forEach((k, v) -> sb.append(claude.underempire.core.Words.count(v, k)).append(", "));
        long warp = beasts(level).stream().filter(boon()::touched).count();
        sb.append(whelped).append(" whelped, ").append(born).append(" bred, ").append(carved).append(" cuts carved, ")
                .append(butchered).append(" butchered, ").append(milked).append(" milk");
        if (warp > 0 || warped > 0) {
            sb.append("; ").append(warp).append(" warp-touched (").append(sickened).append(" sickened, ").append(feral).append(" turned feral, ")
                    .append(putDown).append(" put down)");
        }
        out.add(Component.literal(sb.toString()).withStyle(ChatFormatting.DARK_GREEN));
    }

    @Override
    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putBoolean("stocked", stocked);
        t.putLong("born", born);
        t.putLong("butchered", butchered);
        t.putLong("whelped", whelped);
        t.putLong("carved", carved);
        t.putLong("warped", warped);
        t.putLong("renewed", renewed);
        t.putLong("sickened", sickened);
        t.putLong("feral", feral);
        t.putLong("put_down", putDown);
        return t;
    }

    @Override
    public void load(CompoundTag t) {
        stocked = t.getBoolean("stocked");
        born = t.getLong("born");
        butchered = t.getLong("butchered");
        whelped = t.getLong("whelped");
        carved = t.getLong("carved");
        warped = t.getLong("warped");
        renewed = t.getLong("renewed");
        sickened = t.getLong("sickened");
        feral = t.getLong("feral");
        putDown = t.getLong("put_down");
    }
}
