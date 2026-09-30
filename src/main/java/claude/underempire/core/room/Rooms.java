package claude.underempire.core.room;

import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;

import claude.underempire.core.Core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every room a settlement can dig from a plan, by id: what each race registers at start-up ({@link #register}; Under-Empire's
 * generated catalog is nest/RoomCatalog). A room's sizes, entry door, other doorways (exits), the spots a way down can open
 * off (annexes) and its work stations are in template-local coordinates; the door is the west-facing doorway cell at
 * standing height, on the x=0 face. Templates live at data/&lt;namespace&gt;/structure/room/&lt;id&gt;.nbt.
 */
public final class Rooms {

    /**
     * What a room is for. The engine's own kinds are here; a race adds its own with {@link #of} (Under-Empire's
     * Fleshpits, Throne Hall and the rest: nest/Rooms.Function). One instance per id, so {@code ==} compares.
     */
    public static final class Function {
        private static final Map<String, Function> BY_ID = new LinkedHashMap<>();

        public static final Function TUNNEL = of("TUNNEL");
        public static final Function HALL = of("HALL");
        public static final Function HEART = of("HEART");
        public static final Function DEEP = of("DEEP");
        public static final Function FORGE = of("FORGE");
        public static final Function OREMINE = of("OREMINE");
        public static final Function HOARD = of("HOARD");
        public static final Function FIELDS = of("FIELDS");
        public static final Function DEN = of("DEN");
        public static final Function GROVE = of("GROVE");
        public static final Function PENS = of("PENS");
        public static final Function KITCHEN = of("KITCHEN");
        public static final Function ORCHARD = of("ORCHARD");

        private final String id;

        private Function(String id) {
            this.id = id;
        }

        /** The function with this id, made the first time it is asked for. */
        public static synchronized Function of(String id) {
            return BY_ID.computeIfAbsent(id, Function::new);
        }

        public String id() {
            return id;
        }

        /** As the enum it used to be named it. */
        public String name() {
            return id;
        }

        @Override
        public String toString() {
            return id;
        }
    }

    /** A cell and a way out of it: a doorway and where it faces, or where a way down would start and run. */
    public record Spot(int x, int y, int z, Direction facing) {
    }

    /** A named spot a room's crew works at (an Ore Mine's drift mouths, a Fungus Grove's planting spots, the Beast Pens' paddocks). */
    public record Station(String role, int x, int y, int z, Direction facing) {
    }

    public record Room(String id, String displayName, int cost, Function function, String description,
                       int sizeX, int sizeY, int sizeZ, int doorX, int doorY, int doorZ, List<Spot> exits, List<Spot> annexes,
                       List<Station> stations) {
    }

    /** A deep level's piece: which level it belongs to, what it is (way, room, end, hub) and how often. */
    public record Deep(String id, int tier, String kind, int weight) {
    }

    /**
     * One race's rooms: the ones a plan can build ({@code plans}), the ones a settlement makes for itself ({@code others}:
     * its heart's chamber, the way down, the deep levels' pieces), the deep pieces, the rooms a way down opens off, and
     * how far one way down goes.
     */
    public record Catalog(List<Room> plans, List<Room> others, List<Deep> deepPieces, Set<String> deepSources, int deepStep) {
    }

    private static final Map<String, Room> BY_ID = new LinkedHashMap<>();
    private static final List<Room> PLANS = new ArrayList<>();
    private static final List<Room> OTHERS = new ArrayList<>();
    private static final List<Deep> DEEP_PIECES = new ArrayList<>();
    private static final Set<String> DEEP_SOURCES = new LinkedHashSet<>();
    private static int deepStep = 24;
    /** Whose data each room's template and relight are in: the namespace of the mod that registered it. */
    private static final Map<String, String> NAMESPACE = new HashMap<>();
    private static final Set<String> NAMESPACES = new LinkedHashSet<>();

    private Rooms() {
    }

    /**
     * A race's rooms into the engine, their templates and relights read from {@code namespace}: its own mod id, named by the
     * race (at start-up, from its mod's constructor; a room id registered twice keeps the later one). Never guessed from the
     * mod being constructed: a race registering from an event or a helper would get another mod's (docs/plans/core-step2.md).
     */
    public static synchronized void register(String namespace, Catalog c) {
        NAMESPACES.add(namespace);
        for (Room r : c.plans()) {
            NAMESPACE.put(r.id(), namespace);
        }
        for (Room r : c.others()) {
            NAMESPACE.put(r.id(), namespace);
        }
        for (Deep d : c.deepPieces()) {
            NAMESPACE.put(d.id(), namespace);
        }
        for (Room r : c.plans()) {
            PLANS.removeIf(x -> x.id().equals(r.id()));
            PLANS.add(r);
            BY_ID.put(r.id(), r);
        }
        for (Room r : c.others()) {
            OTHERS.removeIf(x -> x.id().equals(r.id()));
            OTHERS.add(r);
            BY_ID.put(r.id(), r);
        }
        DEEP_PIECES.removeIf(d -> c.deepPieces().stream().anyMatch(n -> n.id().equals(d.id())));
        DEEP_PIECES.addAll(c.deepPieces());
        DEEP_SOURCES.addAll(c.deepSources());
        deepStep = c.deepStep();
    }

    /**
     * Where to look for a room's data, in order: the namespace its mod registered it under, then every race's, then the
     * engine's own (a room id no race registered: a template a race ships without a catalog entry).
     */
    public static synchronized List<String> namespacesFor(String id) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String own = NAMESPACE.get(id);
        if (own != null) {
            out.add(own);
        }
        out.addAll(NAMESPACES);
        out.add(Core.MODID);
        return new ArrayList<>(out);
    }

    @Nullable
    public static Room get(String id) {
        return BY_ID.get(id);
    }

    /** Every room a plan can build. */
    public static List<Room> plans() {
        return Collections.unmodifiableList(PLANS);
    }

    /** The rooms a settlement makes for itself. */
    public static List<Room> others() {
        return Collections.unmodifiableList(OTHERS);
    }

    public static List<Deep> deepPieces() {
        return Collections.unmodifiableList(DEEP_PIECES);
    }

    /** Rooms a way down opens off. */
    public static Set<String> deepSources() {
        return Collections.unmodifiableSet(DEEP_SOURCES);
    }

    /** How far down one way down goes: one level. */
    public static int deepStep() {
        return deepStep;
    }
}
