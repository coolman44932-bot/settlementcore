package claude.settlementcore.test;

import claude.settlementcore.Core;
import claude.settlementcore.defence.Muster;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.settlement.SettlementBlockEntity;
import claude.settlementcore.settlement.SettlementSpawns;
import claude.settlementcore.test.SettlementBlockEntityGameTests.TestSettlement;
import claude.settlementcore.unit.SettlerMob;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The muster across a save (core/defence/Muster#save and #load, saved with the settlement by SettlementBlockEntity): the bug audit of
 * 2026-11-04 found it kept its defenders in memory only. Defenders rise out of the reserve on no roll, counted only through the muster,
 * so a race whose settlers are saved as mobs (Khazuk!'s Dawi) had them come back after a reload on no list: the population did not count
 * them, nothing stood them down, and the race's absorb refused them (Muster#defends false), so they were lost. And a defender whose chunk
 * merely unloaded mid-alarm was dropped from the list at once, and came back an orphan the same way.
 *
 * <p>The engine has no mob of its own, so the defenders here are a settler of the tests' own ({@link Fake}) wearing a pig's type: it
 * thinks nothing (no AI), and says it went home when told. The ground is MusterGameTests' plot of open grass, and the defenders come up
 * through a stub settlement that musters under the sky, into the muster of a test settlement (TestSettlement) that is saved and loaded.
 */
@GameTestHolder(Core.MODID)
public class MusterSaveGameTests {

    /** A settler of the tests: a pig's type, no AI, bound to a settlement, and what it was told kept. */
    static final class Fake extends SettlerMob {
        Settlement home;
        /** Whether it was told to go home. */
        boolean told;
        /** Whether it is saved as a mob (the Waaagh!'s defenders are not: Greenskin's shouldBeSaved). */
        boolean saved = true;
        /** Whether it is a room's crew (DigCrowdGameTests: a crew settler at a chore is not shoved). */
        boolean crew;

        @SuppressWarnings("unchecked")
        Fake(ServerLevel level, Settlement home) {
            super((EntityType<? extends SettlerMob>) (EntityType<?>) EntityType.PIG, level);
            this.home = home;
            setNoAi(true);
            setPersistenceRequired();
        }

        /** One at {@code at}, in the world. */
        static Fake at(ServerLevel level, Settlement home, BlockPos at, @Nullable UUID id) {
            Fake f = new Fake(level, home);
            if (id != null) {
                f.setUUID(id);
            }
            f.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
            level.addFreshEntity(f);
            return f;
        }

        @Override
        public boolean shouldBeSaved() {
            return saved && super.shouldBeSaved();
        }

        @Override
        public Settlement settlement() {
            return home;
        }

        @Override
        public void bindTo(Settlement settlement) {
            home = settlement;
        }

        @Override
        public float tempo() {
            return 1f;
        }

        @Override
        public int animLag() {
            return 0;
        }

        @Override
        public Mode getMode() {
            return Mode.GUARD;
        }

        @Override
        public void setMode(Mode m) {
        }

        @Override
        public boolean isCrew() {
            return crew;
        }

        @Override
        public void makeCrew(@Nullable BlockPos room) {
        }

        @Override
        public BlockPos crewRoom() {
            return null;
        }

        @Override
        public ItemStack carried() {
            return ItemStack.EMPTY;
        }

        @Override
        public void carry(ItemStack load) {
        }

        @Override
        public boolean isDigging() {
            return false;
        }

        @Override
        public void setDigging(boolean d) {
        }

        @Override
        public void goHome() {
            told = true;
        }

        @Override
        public boolean isHomeward() {
            return told;
        }

        @Override
        public boolean isBurrowing() {
            return false;
        }

        @Override
        public void burrowTo(double x, double y, double z) {
        }

        @Override
        public void emergeAt(double x, double y, double z) {
        }

        @Override
        public boolean isBroken() {
            return false;
        }

        @Override
        public boolean isRogue() {
            return false;
        }

        @Override
        public void hearten(float amount) {
        }

        @Override
        public String runningGoals() {
            return "";
        }
    }

    /**
     * A settlement that raises the defenders of {@code home}'s muster on {@code plot} (as MusterGameTests' city): it holds the plot, musters
     * under the sky, has a reserve of 6, and each rise is a {@link Fake} bound to {@code home}, up to {@code most} of them; after that none.
     */
    private static Settlement raiser(ServerLevel level, BlockPos plot, Settlement home, List<Fake> made, int most) {
        return new StubSettlement()
                .is("getBlockPos", plot)
                .is("owner", UUID.randomUUID())
                .is("settlementBoxes", List.of())
                .answer("inSettlement", a -> near((BlockPos) a[0], plot, 20))
                .is("mustersUnderSky", true)
                .is("placeOf", "")
                .quiet("tellOwner")
                .is("reserve", 6)
                .is("reserveWorkers", 6)
                .is("reserveSoldiers", 0)
                .answer("riseFromReserve", a -> {
                    if (made.size() >= most) {
                        return null;
                    }
                    Fake f = Fake.at(level, home, (BlockPos) a[1], null);
                    made.add(f);
                    return f;
                })
                .settlement();
    }

    private static boolean near(BlockPos a, BlockPos b, int r) {
        return Math.abs(a.getX() - b.getX()) <= r && Math.abs(a.getZ() - b.getZ()) <= r && Math.abs(a.getY() - b.getY()) <= r;
    }

    /** A test settlement with its heart at {@code at} (absolute), not placed, given the level, making no one of its own. */
    private static TestSettlement heart(GameTestHelper h, BlockPos at) {
        TestSettlement s = new TestSettlement(at, Blocks.CHEST.defaultBlockState());
        s.setLevel(h.getLevel());
        s.dormant = true;
        return s;
    }

    /** {@code n} seconds of the settlement's ticker. */
    private static void seconds(GameTestHelper h, TestSettlement s, int n) {
        for (int i = 0; i < n * 20; i++) {
            SettlementBlockEntity.serverTick(h.getLevel(), s.getBlockPos(), s.getBlockState(), s);
        }
    }

    /** The race's absorb, as Khazuk!'s Shrine and the test race's Heart write it: one of its own (on the roll, or the muster's) back into the reserve. */
    private static boolean absorb(SettlementBlockEntity home, SettlerMob unit) {
        UUID id = unit.getUUID();
        boolean person = home.hasPerson(id);
        if (!person && !home.muster().defends(id)) {
            return false;
        }
        if (person) {
            home.removePerson(id);
        }
        home.addToReserve(unit.isWorker() ? 1 : 0, unit.isWorker() ? 0 : 1);
        return true;
    }

    /**
     * A settlement saved mid-alarm and loaded into a fresh one keeps its muster: the defenders it raised are its defenders still, counted in
     * its population, and the alarm is up; with no foe left its all-clear comes after {@link Muster#ALL_CLEAR} quiet seconds as it would have,
     * stands every one of them down (told to go home, walking home), and the race's absorb takes each back into the reserve; once each has
     * sunk (discarded) the muster has none left, and the population is the reserve's, counted once.
     */
    @GameTest(template = "digsite", timeoutTicks = 200, batch = "settlement")
    public static void aMusterSavedMidAlarmComesBackAndStandsItsDefendersDown(GameTestHelper h) {
        BlockPos middle = MusterGameTests.openGround(h);
        ServerLevel level = h.getLevel();
        List<Fake> fakes = new ArrayList<>();
        Hoglin foe = null;
        TestSettlement s = heart(h, middle), back = null;
        try {
            s.claim(h.makeMockServerPlayerInLevel(), "Karak Test");
            foe = MusterGameTests.foeAt(level, middle.offset(10, 0, 0), true);
            int first = Math.min(Muster.STEP, Math.min(Muster.max(), Muster.perFoe()));   // a second's defenders for the one foe
            s.muster().second(level, raiser(level, middle, s, fakes, first));
            Set<UUID> raised = s.muster().defenderIds();
            h.assertTrue(first > 0 && s.muster().alarmed() && raised.size() == first && fakes.size() == first,
                    "the foe raised " + raised.size() + " defenders (" + fakes.size() + " made), not " + first);
            foe.discard();

            CompoundTag tag = s.saved(level.registryAccess());
            h.assertTrue(tag.contains("muster"), "the settlement was saved mid-alarm with " + raised.size() + " defenders up and no muster in its tag: "
                    + tag.keySet());
            back = heart(h, middle);
            back.load(tag, level.registryAccess());
            for (Fake f : fakes) {
                f.bindTo(back);                                    // as a reloaded settler finds its heart again
            }
            for (UUID id : raised) {
                h.assertTrue(back.muster().defends(id), "a defender up when the settlement was saved is not the loaded muster's: the race's absorb would refuse it");
            }
            h.assertTrue(back.muster().alarmed() && back.muster().defenderIds().equals(raised), "the loaded muster is "
                    + (back.muster().alarmed() ? "alarmed" : "at peace") + " with " + back.muster().defenderIds().size() + " defenders, not alarmed with " + raised.size());
            h.assertTrue(back.population() == raised.size(), "the loaded settlement counts " + back.population() + " people, not its " + raised.size()
                    + " defenders up (the brood would breed past its cap)");

            seconds(h, back, Muster.ALL_CLEAR - 1);
            h.assertTrue(back.muster().alarmed(), "the all-clear came before " + Muster.ALL_CLEAR + " quiet seconds");
            seconds(h, back, 1);
            h.assertTrue(!back.muster().alarmed() && back.muster().defenderIds().isEmpty() && back.muster().homewardIds().equals(raised),
                    "after " + Muster.ALL_CLEAR + " quiet seconds the loaded muster is " + (back.muster().alarmed() ? "alarmed" : "at peace") + " with "
                            + back.muster().defenderIds().size() + " defenders and " + back.muster().homewardIds().size() + " homeward, not at peace with "
                            + raised.size() + " homeward");
            h.assertTrue(fakes.stream().allMatch(f -> f.told), "a defender was not told to go home at the all-clear");
            h.assertTrue(back.heard.stream().anyMatch(l -> l.contains("clear")), "the owner was not told the all-clear: " + back.heard);
            h.assertTrue(back.population() == raised.size(), "walking home the settlement counts " + back.population() + ", not " + raised.size());

            for (Fake f : fakes) {
                h.assertTrue(absorb(back, f), "the race's absorb refused a defender walking home after the reload");
                f.discard();                                       // sunk: the race discards it
            }
            h.assertTrue(back.reserve() == raised.size() && back.muster().homewardIds().isEmpty() && !back.muster().defends(fakes.get(0).getUUID()),
                    "after the absorbs the reserve is " + back.reserve() + " (not " + raised.size() + "), and the muster still has "
                            + back.muster().homewardIds().size() + " homeward");
            h.assertTrue(back.population() == raised.size(), "home again the settlement counts " + back.population() + ", not " + raised.size()
                    + " once each");
        } finally {
            for (Fake f : fakes) {
                f.discard();
            }
            if (foe != null) {
                foe.discard();
            }
            s.setRemoved();
            if (back != null) {
                back.setRemoved();
            }
            MusterGameTests.clearGround(h, middle);
        }
        h.succeed();
    }

    /**
     * Which defenders the muster keeps as they leave the world mid-alarm: one whose chunk unloads (it chased a foe off) is kept, counted, saved
     * and stood down at the all-clear, and told to go home the second it is back; one killed or sunk (discarded), or one its race never saves
     * as a mob (the Waaagh!'s), is forgotten as it goes; one never saved is not written while it is up, though it is kept; and one out of the
     * world for good is dropped after {@link Muster#MISSING_SECONDS} of the muster's seconds, not before.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "settlement")
    public static void aDefenderOutOfTheWorldIsKeptAndOneGoneIsNot(GameTestHelper h) {
        BlockPos middle = MusterGameTests.openGround(h);
        ServerLevel level = h.getLevel();
        List<Fake> fakes = new ArrayList<>();
        List<Entity> others = new ArrayList<>();
        TestSettlement s = heart(h, middle);
        try {
            h.assertTrue(Muster.STEP >= 3 && Math.min(Muster.max(), 2 * Muster.perFoe()) == 6, "the muster's config is not the default (3 a foe, 40 at most)");
            s.claim(h.makeMockServerPlayerInLevel(), "Karak Test");
            SettlementSpawns.add(level, s);                       // loaded, as a ticking settlement is: it hears its settlers leave
            others.add(MusterGameTests.foeAt(level, middle.offset(10, 0, 0), true));
            others.add(MusterGameTests.foeAt(level, middle.offset(10, 0, 4), true));
            Settlement raiser = raiser(level, middle, s, fakes, 6);
            Muster m = s.muster();
            m.second(level, raiser);
            m.second(level, raiser);
            h.assertTrue(fakes.size() == 6 && m.defenderIds().size() == 6, "two foes raised " + m.defenderIds().size() + " defenders, not 6");
            Fake back = fakes.get(0), away = fakes.get(1), unsaved = fakes.get(2), killed = fakes.get(3), upUnsaved = fakes.get(4), up = fakes.get(5);
            UUID backId = back.getUUID(), awayId = away.getUUID();
            unsaved.saved = false;
            upUnsaved.saved = false;
            back.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);   // their chunks unload
            away.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            unsaved.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
            killed.discard();
            m.second(level, raiser);                               // the foes are still in: the alarm stays up, the lists are pruned
            m.second(level, raiser);
            h.assertTrue(m.alarmed(), "the alarm went down with the foes still in");
            h.assertTrue(m.defends(backId) && m.defends(awayId), "a defender whose chunk unloaded mid-alarm was dropped from the muster (it comes back an orphan)");
            h.assertTrue(!m.defends(unsaved.getUUID()), "a defender its race never saves was kept after it left the world");
            h.assertTrue(!m.defends(killed.getUUID()), "a defender discarded was kept");
            h.assertTrue(m.defends(upUnsaved.getUUID()) && m.defends(up.getUUID()), "a defender up and alive was dropped");
            h.assertTrue(m.out(level) == 4, "the muster counts " + m.out(level) + " out, not the 2 up and the 2 out of the world");

            CompoundTag t = m.save(level);
            Muster loaded = new Muster();
            loaded.load(t == null ? new CompoundTag() : t);
            Set<UUID> want = new LinkedHashSet<>(List.of(backId, awayId, up.getUUID()));
            h.assertTrue(loaded.alarmed() && loaded.defenderIds().equals(want), "the muster saved " + loaded.defenderIds().size()
                    + " defenders (" + (loaded.alarmed() ? "alarmed" : "at peace") + "), not the 3 that will be in the world after a reload, alarmed");

            for (Entity e : others) {
                e.discard();
            }
            for (int i = 0; i < Muster.ALL_CLEAR; i++) {
                m.second(level, raiser);
            }
            h.assertTrue(!m.alarmed() && m.homewardIds().equals(new LinkedHashSet<>(List.of(backId, awayId, upUnsaved.getUUID(), up.getUUID()))),
                    "after the all-clear the muster is " + (m.alarmed() ? "alarmed" : "at peace") + " with " + m.homewardIds().size() + " homeward, not 4");
            h.assertTrue(up.told && upUnsaved.told, "a defender up was not told to go home at the all-clear");

            Fake returned = Fake.at(level, s, middle.offset(3, 0, 3), backId);   // its chunk loads again
            fakes.add(returned);
            m.second(level, raiser);
            h.assertTrue(returned.told && m.defends(backId), "a defender stood down out of the world was not told to go home when it was back");
            returned.discard();                                    // sunk
            up.discard();
            upUnsaved.discard();
            h.assertTrue(m.homewardIds().equals(Set.of(awayId)), "after three sank the homeward are " + m.homewardIds().size() + ", not the one out of the world");

            for (int i = 0; i < Muster.MISSING_SECONDS - 20; i++) {
                m.second(level, raiser);
            }
            h.assertTrue(m.defends(awayId), "a defender out of the world was given up before " + Muster.MISSING_SECONDS + " seconds");
            for (int i = 0; i < 20; i++) {
                m.second(level, raiser);
            }
            h.assertTrue(!m.defends(awayId) && m.homewardIds().isEmpty() && m.save(level) == null,
                    "a defender out of the world for " + Muster.MISSING_SECONDS + " seconds is kept: the homeward grow for ever");
        } finally {
            for (Fake f : fakes) {
                f.discard();
            }
            for (Entity e : others) {
                e.discard();
            }
            s.setRemoved();
            MusterGameTests.clearGround(h, middle);
        }
        h.succeed();
    }
}
