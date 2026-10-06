package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.defence.Muster;
import claude.underempire.core.room.DeepLevels;
import claude.underempire.core.room.PlanPlacement;
import claude.underempire.core.room.RaiseJob;
import claude.underempire.core.room.Rooms;
import claude.underempire.core.settlement.Settlement;
import claude.underempire.core.test.SettlementBlockEntityGameTests.TestSettlement;
import claude.underempire.core.test.SitingGameTests.Site;
import claude.underempire.core.work.SiteWork;
import claude.underempire.core.work.Works;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The engine's words name no race (docs/plans/greatplan-first-spawning.md, section B item 6): a building is raised, its site is reported on,
 * refused, called off, torn down and handed over on a settlement of the tests' own, and every line the engine says about it, to the
 * owner or in a report, is read for a word of another race's: rat, warlord, clan, clanrat, warren, horde, heart, warpstone, slave, skaven
 * (word-bounded, and plural). A word found is fixed with a seam, a word of {@link claude.underempire.core.settlement.Settlement}, never an
 * exception here (the first it found: a refusal that said "raise it near its heart", now {@code Settlement#centreName}). The muster's alarm and
 * all-clear lines are read the same way (its words: {@code Settlement#musterWords}).
 */
@GameTestHolder(Core.MODID)
@PrefixGameTestTemplate(false)
public class EngineWordsGameTests {

    /** The words of the races; "settlement", "builders" and "currency" are the engine's. */
    static final Pattern RACE_WORDS = Pattern.compile("\\b(rats?|warlords?|clans?|clanrats?|warrens?|hordes?|hearts?|warpstone|slaves?|skaven|gnaw(s|ed|ing)?)\\b", Pattern.CASE_INSENSITIVE);

    /** The first race's word in {@code line}, or null. */
    static String raceWordIn(String line) {
        Matcher m = RACE_WORDS.matcher(line);
        return m.find() ? m.group() : null;
    }

    /** The words themselves, on lines that have them and lines that do not (the check can fail). */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void theCheckFindsAWordOfARaceAndNothingElse(GameTestHelper h) {
        String[] bad = {"drifts 40% gnawed", "Raise it near its heart", "The warren is attacked!", "the Horde rises", "5 slaves came up", "Clan Hoard: 3 barrels", "paid in warpstone", "a Clanrat", "Skaven work"};
        String[] good = {"The Test Platform is raised.", "costs 10 of the settlement's currency, and it has 3", "Heartwood and pirates and rate and slavery", "your builders are pulling it down",
                "is outside the settlement: raise it near its centre, or beside what it has built"};
        for (String line : bad) {
            h.assertTrue(raceWordIn(line) != null, "\"" + line + "\" has a race's word and the check did not find it");
        }
        for (String line : good) {
            h.assertTrue(raceWordIn(line) == null, "\"" + line + "\" has no race's word and the check found " + raceWordIn(line));
        }
        h.succeed();
    }

    /**
     * Every line a raise says on a settlement of the tests' own: the refusals of {@code Works#raise} (a dug room, a door outside the
     * settlement, a lava pocket in the box, a price it cannot pay, a second building at the same door), the site's report and needs, the call-off
     * with some of it laid ("is called off") and its teardown ("is down", the price paid back), and a building finished and handed over ("is raised").
     * None has a race's word.
     */
    @GameTest(template = "digsite", timeoutTicks = 400, batch = "settlement")
    public static void everyLineARaiseSaysNamesNoRace(GameTestHelper h) {
        Site s = new Site(h);
        s.lay((x, z) -> s.floor);
        h.runAfterDelay(20, () -> s.guard(() -> {
            ServerLevel level = s.level;
            TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
            Works works = t.works();
            BlockPos door = s.pl.door();
            List<String> said = new ArrayList<>();

            // the refusals, before anything is begun
            String outside = works.raise(level, s.pl, true, true);
            h.assertTrue(outside != null && outside.contains("outside the settlement"), "a door outside the settlement was not refused for that: " + outside);
            said.add(outside);
            t.holds = true;
            Rooms.Room dugRoom = new Rooms.Room("engine_words_dug_room", "Test Dug Room", 0, Rooms.Function.HALL, "A dug room of the engine-words test, registered nowhere.",
                    3, 3, 3, 0, 1, 1, List.of(), List.of(), List.of());
            h.assertTrue(!Rooms.isRaised(dugRoom.id()), "no dug room to try: " + dugRoom.id() + " is a raised building");
            String dug = works.raise(level, PlanPlacement.compute(dugRoom, door, Direction.EAST), true, true);
            h.assertTrue(dug != null && dug.contains("dug, not raised"), "a dug room was not refused for that: " + dug);
            said.add(dug);
            BlockPos lava = new BlockPos(s.bx0 + 4, s.floor, s.bz0 + 4);
            s.set(lava.getX(), lava.getY(), lava.getZ(), Blocks.LAVA.defaultBlockState());
            String burning = works.raise(level, s.pl, true, true);
            h.assertTrue(burning != null && burning.contains("Lava"), "lava in the box was not refused for that: " + burning);
            said.add(burning);
            s.set(lava.getX(), lava.getY(), lava.getZ(), Blocks.GRASS_BLOCK.defaultBlockState());
            t.addCurrency(3, "test");
            String poor = works.raise(level, s.pl, false, true);
            h.assertTrue(poor != null && poor.contains("costs") && poor.contains("it has 3"), "a price too high was not refused for that: " + poor);
            said.add(poor);

            // begun, paid for, and refused a second time at the same door; the site reported on
            t.addCurrency(50, "test");
            String begun = works.raise(level, s.pl, false, true);
            h.assertTrue(begun == null, "the building was refused with the price in hand: " + begun);
            String twice = works.raise(level, s.pl, false, true);
            h.assertTrue(twice != null && twice.contains("already"), "a second building at the same door was not refused for that: " + twice);
            said.add(twice);
            SiteWork site = works.sites().get(0);
            report(level, site, said);

            // called off with some of it laid, then down (the price paid back)
            RaiseJob job = site.job();
            job.ensureLoaded(level);
            int firstCourse = 0;
            while (firstCourse < job.taskList().size() && job.phaseOf(firstCourse) != RaiseJob.COURSE) {
                firstCourse++;
            }
            job.gnawInOrder(level, firstCourse + 1, new ArrayList<>());          // the clearing and earthworks, and one cell of the first course
            h.assertTrue(job.laidCount() == 1, "one cell of the building was to be laid, not " + job.laidCount());
            report(level, site, said);
            Works.Cancelled off = works.cancel(level, door);
            h.assertTrue(off != null && !off.atOnce(), "a building half-laid was not called off for the builders to take down: " + off);
            report(level, site, said);
            job.tearSelf(level, 10_000);
            works.second(level, 100);
            h.assertTrue(works.sites().isEmpty() && t.heard.stream().anyMatch(l -> l.contains("is down")), "the site that was called off is not down, or the owner was not told: " + t.heard);

            // another at the same door, on the ground laid again, finished and handed over
            s.lay((x, z) -> s.floor);
            String again = works.raise(level, s.pl, true, true);
            h.assertTrue(again == null, "the building was refused after the other was down: " + again);
            works.sites().get(0).job().finishAll(level);
            works.second(level, 101);
            h.assertTrue(works.sites().isEmpty() && t.heard.stream().anyMatch(l -> l.contains("is raised")), "the finished building was not handed over, or the owner was not told: " + t.heard);

            said.addAll(t.heard);
            h.assertTrue(said.size() >= 9, "only " + said.size() + " lines were said: the test does not reach the engine's words");
            for (String line : said) {
                String word = raceWordIn(line);
                h.assertTrue(word == null, "the engine said \"" + line + "\", with the race's word \"" + word + "\": a seam (a Settlement word) is owed, not an exception here");
            }
            t.setRemoved();
            s.clear();
            h.succeed();
        }));
    }

    /**
     * The engine's own words for what every race's settlement says outside a raise and the muster (the review of d21aef0: the deep levels, the
     * Furnace Chambers, the Hoard, the Cook-Pits, the Den, the haul log and the standing orders still spoke the Skaven's): a settlement of the
     * tests' own that names nothing is called the "settlement", its people "your people", its Hoard and spoils in the Ledger "Hoard" and
     * "Spoils", its currency "the settlement's currency in store"; every line about digging down built of the engine's words, for each level,
     * and the orders. None has a race's word.
     */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void theEnginesOwnWordsNameNoRace(GameTestHelper h) {
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        Settlement.DeepWords w = t.deepWords();
        h.assertTrue(w == Settlement.DeepWords.ENGINE, "a settlement of the tests' own has deep words of its own: " + w);
        List<String> said = new ArrayList<>(List.of(t.placeName(), t.peopleName(), t.hoardName(), t.spoilsName(), t.currencyHeld(), t.currencyAmount(12),
                claude.underempire.core.ledger.HaulLog.LARDER, claude.underempire.core.ledger.HaulLog.WARLORD, claude.underempire.core.ledger.HaulLog.EATEN,
                DeepLevels.noWayDown(w, "the Test Hall"), DeepLevels.wayCutInto(w, "the Test Hall", ""), DeepLevels.wayCutInto(w, "the Test Hall", "the Test Store")));
        for (int tier = 0; tier <= 4; tier++) {
            said.add(DeepLevels.begunDown(w, "the Test Hall", tier));
            said.add(DeepLevels.levelDug(w, "the Test Hall", tier));
            said.add(DeepLevels.levelName(tier));
        }
        for (claude.underempire.core.unit.SettlerMob.Mode m : claude.underempire.core.unit.SettlerMob.Mode.values()) {
            said.add(m.order());
        }
        for (String line : said) {
            String word = raceWordIn(line);
            h.assertTrue(word == null, "the engine says \"" + line + "\", with the race's word \"" + word + "\": a seam (a Settlement word) is owed");
        }
        h.assertTrue(DeepLevels.levelDug(w, "the Test Hall", 1).equals("The Deep Halls beneath the Test Hall are dug out. Go down: the deeper you go, the more of your people you will see.")
                        && DeepLevels.levelDug(w, "the Test Hall", 3).equals("The Deepest Hall (3 down) beneath the Test Hall is dug out. Your people are thickest here."),
                "the levels dug read " + DeepLevels.levelDug(w, "the Test Hall", 1) + " / " + DeepLevels.levelDug(w, "the Test Hall", 3));
        t.setRemoved();
        h.succeed();
    }

    /**
     * The rooms' own lines on a settlement of the tests' own that names nothing (the review of df7b881: the words test read the seams, not
     * the lines): a Furnace Chambers, a Cook-Pits, a Fields, a Hoard and an Ore Mine of the engine's kinds, named by the test, each built bare
     * in the test's plot; their reports, gauges, needs and boosts, and the Forge's and the Mine's night, carry no race's word.
     */
    @GameTest(template = "digsite", timeoutTicks = 40, batch = "settlement")
    public static void theRoomsOwnLinesNameNoRace(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        List<String> said = new ArrayList<>();
        Rooms.Function[] kinds = {Rooms.Function.FORGE, Rooms.Function.KITCHEN, Rooms.Function.FIELDS, Rooms.Function.HOARD, Rooms.Function.OREMINE};
        for (Rooms.Function f : kinds) {
            String id = "engine_words_" + f.id().toLowerCase(java.util.Locale.ROOT);
            Rooms.Room room = new Rooms.Room(id, "Test " + f.id().charAt(0) + f.id().substring(1).toLowerCase(java.util.Locale.ROOT), 0, f,
                    "A room of the engine-words test, registered nowhere.", 5, 4, 5, 0, 1, 2, List.of(), List.of(), List.of());
            PlanPlacement pl = PlanPlacement.compute(room, h.absolutePos(new BlockPos(1, 2, 3)), Direction.EAST);
            claude.underempire.core.settlement.Building b = new claude.underempire.core.settlement.Building(id, pl.box(), pl.door(), Direction.EAST);
            claude.underempire.core.work.RoomWork w = switch (f.id()) {
                case "FORGE" -> new claude.underempire.core.work.ForgeWork(t, b, pl);
                case "KITCHEN" -> new claude.underempire.core.work.CookWork(t, b, pl);
                case "FIELDS" -> new claude.underempire.core.work.FieldsWork(t, b, pl);
                case "HOARD" -> new claude.underempire.core.work.HoardWork(t, b, pl);
                default -> new claude.underempire.core.work.MineWork(t, b, pl);
            };
            List<Component> lines = new ArrayList<>();
            w.describe(level, lines);
            List<String> needs = new ArrayList<>();
            List<String> boosts = new ArrayList<>();
            w.wants(level, needs, boosts);
            var g = w.gauge(level);
            if (g != null) {
                said.add(g.toString());
            }
            if (f == Rooms.Function.FORGE || f == Rooms.Function.OREMINE) {
                w.nightsWork(level, 60, lines);
            }
            lines.forEach(c -> said.add(c.getString()));
            said.addAll(needs);
            said.addAll(boosts);
        }
        h.assertTrue(said.size() >= kinds.length, "only " + said.size() + " lines were said: the test does not reach the rooms' words");
        for (String line : said) {
            String word = raceWordIn(line);
            h.assertTrue(word == null, "a room of the engine says \"" + line + "\", with the race's word \"" + word + "\": a seam is owed");
        }
        t.setRemoved();
        h.succeed();
    }

    /**
     * The muster's two lines to the owner (core/defence/Muster) on a settlement of the tests' own that gives no words for them: a foe comes into it and
     * the alarm is told; the foe goes, {@link Muster#ALL_CLEAR} quiet seconds pass and the all-clear is told. Neither has a race's word ("The warren is attacked!",
     * "the horde rises", "sink back into the tunnels" were the Skaven's, said to any race: the review of 20da67f). The all-clear's tail, which needs defenders
     * the engine cannot make (it has no mob), is read from the line the muster builds it with, for one defender and for several.
     */
    @GameTest(template = "digsite", timeoutTicks = 100, batch = "settlement")
    public static void theMustersAlarmAndAllClearNameNoRace(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        TestSettlement t = TestSettlement.of(h, new BlockPos(2, 2, 2));
        t.claim(h.makeMockServerPlayerInLevel(), "The Pool of Sotek");
        t.holds = true;
        Hoglin foe = MusterGameTests.foeAt(level, h.absolutePos(new BlockPos(4, 2, 4)), false);
        foe.setNoGravity(true);
        foe.setInvulnerable(true);
        level.addFreshEntity(foe);
        try {
            Muster muster = t.muster();
            muster.second(level, t);
            h.assertTrue(muster.alarmed() && muster.lastSeen == 1, "the foe in the settlement raised no alarm (" + muster.lastSeen + " seen)");
            h.assertTrue(t.heard.size() == 1 && t.heard.get(0).startsWith("The settlement is attacked!"), "the alarm said " + t.heard + ", not a line of the engine's words");
            foe.discard();
            for (int i = 0; i < Muster.ALL_CLEAR; i++) {
                muster.second(level, t);
            }
            h.assertTrue(!muster.alarmed() && t.heard.size() == 2 && t.heard.get(1).startsWith("The settlement is clear:"),
                    "the all-clear said " + t.heard + ", not a second line of the engine's words");
            List<String> said = new ArrayList<>(t.heard);
            said.add(Muster.clearLine(t.musterWords(), 3, 2, 4));
            said.add(Muster.clearLine(t.musterWords(), 1, 0, 1));
            said.add(Muster.attackedLine(t.musterWords(), "3 Zombies and a Pillager", "Test Platform", false));
            h.assertTrue(said.get(2).endsWith("4 defenders stand down.") && said.get(3).endsWith("A defender stands down."), "the all-clear's tail reads " + said.get(2) + " / " + said.get(3));
            for (String line : said) {
                String word = raceWordIn(line);
                h.assertTrue(word == null, "the muster said \"" + line + "\", with the race's word \"" + word + "\": a seam (a Settlement word) is owed, not an exception here");
            }
        } finally {
            foe.discard();
            t.setRemoved();
        }
        h.succeed();
    }

    /** What the site says of itself: its report and what it needs. */
    private static void report(ServerLevel level, SiteWork site, List<String> said) {
        List<Component> out = new ArrayList<>();
        site.describe(level, out);
        for (Component c : out) {
            said.add(c.getString());
        }
        site.wants(level, said, said);
    }
}
