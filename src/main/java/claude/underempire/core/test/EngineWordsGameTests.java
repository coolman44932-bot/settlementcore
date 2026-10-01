package claude.underempire.core.test;

import claude.underempire.core.Core;
import claude.underempire.core.room.PlanPlacement;
import claude.underempire.core.room.RaiseJob;
import claude.underempire.core.room.Rooms;
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
 * exception here (the first it found: a refusal that said "raise it near its heart", now {@code Settlement#centreName}).
 */
@GameTestHolder(Core.MODID)
@PrefixGameTestTemplate(false)
public class EngineWordsGameTests {

    /** The words of the races; "settlement", "builders" and "currency" are the engine's. */
    static final Pattern RACE_WORDS = Pattern.compile("\\b(rats?|warlords?|clans?|clanrats?|warrens?|hordes?|hearts?|warpstone|slaves?|skaven)\\b", Pattern.CASE_INSENSITIVE);

    /** The first race's word in {@code line}, or null. */
    static String raceWordIn(String line) {
        Matcher m = RACE_WORDS.matcher(line);
        return m.find() ? m.group() : null;
    }

    /** The words themselves, on lines that have them and lines that do not (the check can fail). */
    @GameTest(template = "digsite", timeoutTicks = 20, batch = "settlement")
    public static void theCheckFindsAWordOfARaceAndNothingElse(GameTestHelper h) {
        String[] bad = {"Raise it near its heart", "The warren is attacked!", "the Horde rises", "5 slaves came up", "Clan Hoard: 3 barrels", "paid in warpstone", "a Clanrat", "Skaven work"};
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
            Rooms.Room dugRoom = BuildingBookGameTests.cellsRoom();
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
