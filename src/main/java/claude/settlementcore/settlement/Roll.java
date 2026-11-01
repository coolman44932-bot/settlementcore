package claude.settlementcore.settlement;

import claude.settlementcore.Nbt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A settlement's roll: who of its people are up and about as mobs, by id (the Warren Heart's {@code rats}). A settler is on the
 * roll from when it rises out of the reserve to when it dies or goes back down; the world holds the mob itself, and the roll
 * finds the ones that are loaded and alive ({@link #loaded}). Lifted from the Heart (docs/plans/greatplan-first-spawning.md,
 * section B item 2): the ids are saved as the Heart saves them, under a key the race names, so the Heart's {@code rats} tag
 * reads back through here.
 *
 * <p>The roll changes nothing else: its owner calls {@code setChanged} after a change, as the Heart does.
 */
public final class Roll {

    private final String key;
    private final Set<UUID> ids = new LinkedHashSet<>();

    /** @param key the save key of the ids (the Warren Heart's "rats") */
    public Roll(String key) {
        this.key = key;
    }

    /** {@code id} is up. Returns whether it was not on the roll already. */
    public boolean add(UUID id) {
        return ids.add(id);
    }

    /** {@code id} is off the roll. Returns whether it was on it. */
    public boolean remove(UUID id) {
        return ids.remove(id);
    }

    /**
     * {@code id} is off the roll, and if it was on it {@code release} is told, once: whatever it had claimed (a dig's cells, a
     * chore) goes back. Returns whether it was on the roll.
     */
    public boolean remove(UUID id, Consumer<UUID> release) {
        if (!ids.remove(id)) {
            return false;
        }
        release.accept(id);
        return true;
    }

    public boolean has(UUID id) {
        return ids.contains(id);
    }

    public int count() {
        return ids.size();
    }

    /** Everyone on the roll, in the order they were added: a view, read only. */
    public Set<UUID> ids() {
        return Collections.unmodifiableSet(ids);
    }

    /** The roll's mobs of {@code type} that are in a loaded chunk of {@code level} and alive, in the order they were added. */
    public <T extends Entity> List<T> loaded(ServerLevel level, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (UUID id : ids) {
            Entity e = level.getEntity(id);
            if (type.isInstance(e) && e.isAlive()) {
                out.add(type.cast(e));
            }
        }
        return out;
    }

    /** The ids, as a list of int arrays under the roll's key (as the Heart writes {@code rats}). */
    public void save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (UUID id : ids) {
            list.add(Nbt.uuid(id));
        }
        tag.put(key, list);
    }

    /** The roll is what the tag's list under its key says; a tag with none empties it. */
    public void load(CompoundTag tag) {
        ids.clear();
        for (Tag t : tag.getListOrEmpty(key)) {
            ids.add(Nbt.uuid(t));
        }
    }
}
