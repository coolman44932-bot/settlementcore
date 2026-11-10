package claude.settlementcore.dev;

import claude.settlementcore.settlement.Building;
import claude.settlementcore.settlement.Settlement;
import claude.settlementcore.settlement.Settlements;
import claude.settlementcore.unit.SettlerMob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.fml.InterModComms;
import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.InterModEnqueueEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What the engine tells devbridge (block bench/devbridge, Claude's dev-game bridge) when a dev game loads both: a settler's
 * own state for {@code inspect} and its 2-minute history (mode, crew, chore, load, digging, homeward, broken, its
 * settlement), and the routes {@code settlementcore/settlement} (a settlement's people, stores, rooms and works) and
 * {@code settlementcore/ledger} (its Ledger opened for the camera player, to be photographed). Sent through
 * InterModComms with plain Java types, so the engine never depends on devbridge; without it this sends nothing.
 */
public final class BridgeHooks {

    private BridgeHooks() {
    }

    public static void enqueue(InterModEnqueueEvent e) {
        if (!ModList.get().isLoaded("devbridge")) {
            return;
        }
        InterModComms.sendTo("devbridge", "entity", (Supplier<Function<Entity, Map<String, Object>>>) () -> BridgeHooks::settler);
        InterModComms.sendTo("devbridge", "route",
                (Supplier<Map.Entry<String, Function<Map<String, Object>, Object>>>) () -> Map.entry("settlement", BridgeHooks::settlement));
        InterModComms.sendTo("devbridge", "route",
                (Supplier<Map.Entry<String, Function<Map<String, Object>, Object>>>) () -> Map.entry("ledger", BridgeHooks::ledger));
    }

    /**
     * The Ledger opened for the camera player, so the screen can be photographed ({@code look} with {@code gui}): the settlement whose
     * heart is at {@code pos}, else the loaded one nearest the camera. {@code claim}: the camera player is made its owner first (a dev
     * game's settlements are set down by commands and tests, and only its owner opens a Ledger). {@code page}: the open screen is turned
     * to that page (0 Stores, 1 Flows). {@code close}: the open screen is shut. Says what it did.
     */
    public static Object ledger(Map<String, Object> a) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            throw new IllegalArgumentException("no player in the game to open a Ledger for");
        }
        ServerPlayer player = players.get(0);
        Map<String, Object> o = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(a.get("close"))) {
            player.closeContainer();
            o.put("closed", true);
            return o;
        }
        if (a.get("page") instanceof Number page) {
            if (net.neoforged.fml.loading.FMLEnvironment.getDist().isClient()) {
                claude.settlementcore.client.LedgerClient.turnTo(page.intValue());   // (a client's class: named only on a client)
            }
            o.put("page", page.intValue());
            return o;
        }
        ServerLevel level = (ServerLevel) player.level();
        Settlement s = null;
        BlockPos at = pos(a.get("pos"));
        if (at != null) {
            if (!(level.getBlockEntity(at) instanceof Settlement st)) {
                throw new IllegalArgumentException("no settlement's heart at " + at.toShortString());
            }
            s = st;
        } else {
            double best = Double.MAX_VALUE;
            for (Settlement st : claude.settlementcore.settlement.SettlementSpawns.loaded(level)) {
                double d = st.getBlockPos().distSqr(player.blockPosition());
                if (d < best) {
                    best = d;
                    s = st;
                }
            }
        }
        if (s == null) {
            throw new IllegalArgumentException("no settlement loaded in " + level.dimension().identifier());
        }
        if (Boolean.TRUE.equals(a.get("claim")) && s instanceof claude.settlementcore.settlement.SettlementBlockEntity sb) {
            sb.claim(player, sb.settlementName() == null || sb.settlementName().isEmpty() ? "The Dev Settlement" : sb.settlementName());
        }
        o.put("settlement", s.settlementName() + " at " + s.getBlockPos().toShortString());
        o.put("owner", String.valueOf(s.owner()));
        o.put("opened", claude.settlementcore.ledger.LedgerMenu.open(player, s));
        o.put("lines", claude.settlementcore.ledger.SettlementLedger.tally(level, s).lines().size());
        return o;
    }

    /** A settler's own state, or null for any other entity. */
    public static Map<String, Object> settler(Entity e) {
        if (!(e instanceof SettlerMob s)) {
            return null;
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("mode", s.getMode().name());
        o.put("worker", s.isWorker());
        o.put("crew", s.isCrew());
        if (s.isCrew() && s.crewRoom() != null) {
            o.put("crewRoom", s.crewRoom().toShortString());
        }
        o.put("doing", s.crewDoing());
        ItemStack carried = s.carried();
        o.put("carrying", carried.isEmpty() ? "" : carried.getCount() + " " + BuiltInRegistries.ITEM.getKey(carried.getItem()).getPath());
        o.put("digging", s.isDigging());
        o.put("homeward", s.isHomeward());
        o.put("burrowing", s.isBurrowing());
        o.put("broken", s.isBroken());
        o.put("rogue", s.isRogue());
        Settlement home = s.settlement();
        o.put("settlement", home == null ? "" : home.settlementName() + " at " + home.getBlockPos().toShortString());
        return o;
    }

    /**
     * A settlement: the one whose heart is at {@code pos} ([x, y, z]), else the camera player's own, else the nearest one any
     * loaded settler belongs to.
     */
    public static Object settlement(Map<String, Object> a) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(String.valueOf(a.getOrDefault("_dimension", "minecraft:overworld")))));
        if (level == null) {
            level = server.overworld();
        }
        BlockPos camera = pos(a.get("_camera"));
        Settlement s = null;
        BlockPos at = pos(a.get("pos"));
        if (at != null) {
            BlockEntity be = level.getBlockEntity(at);
            if (!(be instanceof Settlement st)) {
                throw new IllegalArgumentException("no settlement's heart at " + at.toShortString());
            }
            s = st;
        } else {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (!players.isEmpty()) {
                s = Settlements.of(players.get(0));
            }
            if (s == null) {
                double best = Double.MAX_VALUE;
                for (Entity e : level.getAllEntities()) {
                    if (e instanceof SettlerMob m && m.settlement() != null) {
                        double d = camera == null ? 0 : m.settlement().getBlockPos().distSqr(camera);
                        if (d < best) {
                            best = d;
                            s = m.settlement();
                        }
                    }
                }
            }
        }
        if (s == null) {
            throw new IllegalArgumentException("no settlement: give pos (its heart), or have one with a loaded settler");
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("name", s.settlementName());
        o.put("heart", s.getBlockPos().toShortString());
        o.put("owner", String.valueOf(s.owner()));
        o.put("people", s.peopleCount() + "/" + s.peopleCap());
        o.put("loaded", s.loadedPeople().size());
        o.put("reserveWorkers", s.reserveWorkers());
        o.put("reserveSoldiers", s.reserveSoldiers());
        o.put("crew", s.works().crewCount());
        o.put("food", s.food());
        o.put("currency", s.currencyAmount(s.currency()));
        List<Map<String, Object>> rooms = new ArrayList<>();
        for (Building b : s.buildings()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("room", b.room());
            BoundingBox box = b.box();
            r.put("box", box.minX() + " " + box.minY() + " " + box.minZ() + " .. " + box.maxX() + " " + box.maxY() + " " + box.maxZ());
            if (b.door() != null) {
                r.put("door", b.door().toShortString());
            }
            rooms.add(r);
        }
        o.put("rooms", rooms);
        List<Component> described = new ArrayList<>();
        s.works().describe(level, described);
        o.put("works", described.stream().map(Component::getString).toList());
        Map<String, Integer> spoils = new HashMap<>();
        for (ItemStack st : s.spoils()) {
            spoils.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
        }
        Map<String, Integer> top = new LinkedHashMap<>();
        spoils.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(30)
                .forEach(en -> top.put(en.getKey(), en.getValue()));
        o.put("spoils", top);
        return o;
    }

    private static BlockPos pos(Object o) {
        if (o instanceof List<?> l && l.size() == 3) {
            return BlockPos.containing(((Number) l.get(0)).doubleValue(), ((Number) l.get(1)).doubleValue(), ((Number) l.get(2)).doubleValue());
        }
        return null;
    }
}
