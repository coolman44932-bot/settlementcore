package claude.settlementcore.test;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A batch's world ({@link BatchWorld}) as values: what to set, and {@link #apply} hands back what was there, to {@link Saved#restore}.
 * The nulls leave the world as it is. 26.1's names: day time is the overworld's {@link WorldClock} (the dimension type's default
 * clock, ServerClockManager#setTotalTicks; Level#getDayTime is gone), the daylight cycle is the {@code advance_time} rule, natural
 * spawning {@code spawn_mobs} (and {@code spawn_monsters}), keepInventory {@code keep_inventory}; game rules are
 * {@code GameRule<T>} values set through {@code GameRules#set(rule, value, server)}, which tells the server (and NeoForge's
 * GameRuleChangedEvent) of each change.
 */
public record WorldConditions(@Nullable Long time, @Nullable Boolean daylightCycle, BatchWorld.@Nullable Weather weather,
                              @Nullable Boolean mobSpawning, @Nullable Difficulty difficulty, @Nullable Boolean keepInventory,
                              @Nullable Integer randomTickSpeed, @Nullable Integer simulationDistance) {

    private static final Logger LOG = LogUtils.getLogger();

    public static final WorldConditions NONE = new WorldConditions(null, null, null, null, null, null, null, null);

    public static WorldConditions of(BatchWorld w) {
        return new WorldConditions(w.time() < 0 ? null : w.time(), rule(w.daylightCycle()), w.weather().length == 0 ? null : w.weather()[0],
                rule(w.mobSpawning()), w.difficulty().length == 0 ? null : w.difficulty()[0], rule(w.keepInventory()),
                w.randomTickSpeed() < 0 ? null : w.randomTickSpeed(), w.simulationDistance() < 0 ? null : w.simulationDistance());
    }

    private static @Nullable Boolean rule(BatchWorld.Rule r) {
        return r == BatchWorld.Rule.LEAVE ? null : r == BatchWorld.Rule.ON;
    }

    public boolean isEmpty() {
        return equals(NONE);
    }

    public WorldConditions withTime(long t) {
        return new WorldConditions(t, daylightCycle, weather, mobSpawning, difficulty, keepInventory, randomTickSpeed, simulationDistance);
    }

    public WorldConditions withDifficulty(Difficulty d) {
        return new WorldConditions(time, daylightCycle, weather, mobSpawning, d, keepInventory, randomTickSpeed, simulationDistance);
    }

    public WorldConditions withKeepInventory(boolean keep) {
        return new WorldConditions(time, daylightCycle, weather, mobSpawning, difficulty, keep, randomTickSpeed, simulationDistance);
    }

    public WorldConditions withRandomTickSpeed(int speed) {
        return new WorldConditions(time, daylightCycle, weather, mobSpawning, difficulty, keepInventory, speed, simulationDistance);
    }

    public WorldConditions withDaylightCycle(boolean on) {
        return new WorldConditions(time, on, weather, mobSpawning, difficulty, keepInventory, randomTickSpeed, simulationDistance);
    }

    public WorldConditions withMobSpawning(boolean on) {
        return new WorldConditions(time, daylightCycle, weather, on, difficulty, keepInventory, randomTickSpeed, simulationDistance);
    }

    public WorldConditions withWeather(BatchWorld.Weather w) {
        return new WorldConditions(time, daylightCycle, w, mobSpawning, difficulty, keepInventory, randomTickSpeed, simulationDistance);
    }

    public WorldConditions withSimulationDistance(int chunks) {
        return new WorldConditions(time, daylightCycle, weather, mobSpawning, difficulty, keepInventory, randomTickSpeed, chunks);
    }

    /** These conditions over {@code base}: what this one sets wins. */
    public WorldConditions over(WorldConditions base) {
        return new WorldConditions(time != null ? time : base.time, daylightCycle != null ? daylightCycle : base.daylightCycle,
                weather != null ? weather : base.weather, mobSpawning != null ? mobSpawning : base.mobSpawning,
                difficulty != null ? difficulty : base.difficulty, keepInventory != null ? keepInventory : base.keepInventory,
                randomTickSpeed != null ? randomTickSpeed : base.randomTickSpeed,
                simulationDistance != null ? simulationDistance : base.simulationDistance);
    }

    /** The overworld's day clock, if the level's dimension has one. */
    public static Optional<Holder<WorldClock>> clock(ServerLevel level) {
        return level.dimensionType().defaultClock();
    }

    /** The world as it is now, in every field (what {@link #apply} hands back to restore). */
    public static WorldConditions read(ServerLevel level) {
        MinecraftServer server = level.getServer();
        GameRules rules = level.getGameRules();
        Long time = clock(level).map(c -> server.clockManager().getTotalTicks(c)).orElse(null);
        // the weather as set (WeatherData), not as it looks yet: Level#isRaining follows a rain level that fades in over ticks
        var w = server.getWeatherData();
        BatchWorld.Weather weather = w.isRaining() && w.isThundering() ? BatchWorld.Weather.THUNDER : w.isRaining() ? BatchWorld.Weather.RAIN : BatchWorld.Weather.CLEAR;
        return new WorldConditions(time, rules.get(GameRules.ADVANCE_TIME), weather, rules.get(GameRules.SPAWN_MOBS), level.getDifficulty(),
                rules.get(GameRules.KEEP_INVENTORY), rules.get(GameRules.RANDOM_TICK_SPEED), server.getPlayerList().getSimulationDistance());
    }

    /**
     * Sets what these conditions name, and returns what was there. spawn_monsters goes on with natural spawning (both must be on
     * for monsters) and is put back too.
     */
    public Saved apply(ServerLevel level) {
        WorldConditions before = read(level);
        boolean monstersBefore = level.getGameRules().get(GameRules.SPAWN_MONSTERS);
        set(level, this, Boolean.TRUE.equals(mobSpawning) ? Boolean.TRUE : null);
        return new Saved(this, before, monstersBefore);
    }

    /** What {@link #apply} replaced. */
    public record Saved(WorldConditions applied, WorldConditions before, boolean monstersBefore) {

        /** Puts back each field the conditions set, and says which did not come back (empty when all did). */
        public List<String> restore(ServerLevel level) {
            WorldConditions back = new WorldConditions(applied.time == null ? null : before.time,
                    applied.daylightCycle == null ? null : before.daylightCycle, applied.weather == null ? null : before.weather,
                    applied.mobSpawning == null ? null : before.mobSpawning, applied.difficulty == null ? null : before.difficulty,
                    applied.keepInventory == null ? null : before.keepInventory, applied.randomTickSpeed == null ? null : before.randomTickSpeed,
                    applied.simulationDistance == null ? null : before.simulationDistance);
            set(level, back, Boolean.TRUE.equals(applied.mobSpawning) ? monstersBefore : null);
            WorldConditions now = read(level);
            List<String> wrong = new ArrayList<>();
            check(wrong, "daylight cycle", back.daylightCycle, now.daylightCycle);
            check(wrong, "weather", back.weather, now.weather);
            check(wrong, "mob spawning", back.mobSpawning, now.mobSpawning);
            check(wrong, "difficulty", back.difficulty, now.difficulty);
            check(wrong, "keepInventory", back.keepInventory, now.keepInventory);
            check(wrong, "random tick speed", back.randomTickSpeed, now.randomTickSpeed);
            check(wrong, "simulation distance", back.simulationDistance, now.simulationDistance);
            if (back.time != null && now.time != null && Math.abs(now.time - back.time) > 1) {
                wrong.add("time " + now.time + " (wanted " + back.time + ")");
            }
            return wrong;
        }

        private static void check(List<String> wrong, String what, @Nullable Object wanted, @Nullable Object now) {
            if (wanted != null && !wanted.equals(now)) {
                wrong.add(what + " " + now + " (wanted " + wanted + ")");
            }
        }
    }

    private static void set(ServerLevel level, WorldConditions c, @Nullable Boolean monsters) {
        MinecraftServer server = level.getServer();
        GameRules rules = level.getGameRules();
        if (c.time != null) {
            clock(level).ifPresentOrElse(clock -> server.clockManager().setTotalTicks(clock, c.time),
                    () -> LOG.warn("BatchWorld: {} has no day clock; time {} not set", level.dimension().identifier(), c.time));
        }
        if (c.daylightCycle != null) {
            setRule(rules, GameRules.ADVANCE_TIME, c.daylightCycle, server);
        }
        if (c.weather != null) {
            // TestEnvironmentDefinition.Weather's own values (its apply is package-private): a long spell, the cycle stopped by the server
            level.resetWeatherCycle();
            switch (c.weather) {
                case CLEAR -> server.setWeatherParameters(100000, 0, false, false);
                case RAIN -> server.setWeatherParameters(0, 100000, true, false);
                case THUNDER -> server.setWeatherParameters(0, 100000, true, true);
            }
        }
        if (c.keepInventory != null) {
            setRule(rules, GameRules.KEEP_INVENTORY, c.keepInventory, server);
        }
        if (c.randomTickSpeed != null) {
            setRule(rules, GameRules.RANDOM_TICK_SPEED, c.randomTickSpeed, server);
        }
        if (c.mobSpawning != null) {
            setRule(rules, GameRules.SPAWN_MOBS, c.mobSpawning, server);
        }
        if (monsters != null) {
            setRule(rules, GameRules.SPAWN_MONSTERS, monsters, server);
        }
        if (c.difficulty != null) {
            server.setDifficulty(c.difficulty, true);        // also updates the levels' spawn flags
        } else if (c.mobSpawning != null || monsters != null) {
            server.updateMobSpawningFlags();                 // only a spawn_monsters change does it by itself
        }
        if (c.simulationDistance != null) {
            server.getPlayerList().setSimulationDistance(c.simulationDistance);
        }
    }

    private static <T> void setRule(GameRules rules, GameRule<T> rule, T value, MinecraftServer server) {
        if (!value.equals(rules.get(rule))) {
            rules.set(rule, value, server);
        }
    }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        if (time != null) parts.add("time " + time);
        if (daylightCycle != null) parts.add("daylight cycle " + (daylightCycle ? "on" : "off"));
        if (weather != null) parts.add(weather.name().toLowerCase(java.util.Locale.ROOT));
        if (mobSpawning != null) parts.add("mob spawning " + (mobSpawning ? "on" : "off"));
        if (difficulty != null) parts.add(difficulty.getSerializedName());
        if (keepInventory != null) parts.add("keepInventory " + (keepInventory ? "on" : "off"));
        if (randomTickSpeed != null) parts.add("random tick speed " + randomTickSpeed);
        if (simulationDistance != null) parts.add("simulation distance " + simulationDistance);
        return parts.isEmpty() ? "as it is" : String.join(", ", parts);
    }
}
