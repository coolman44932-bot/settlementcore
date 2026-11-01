package claude.settlementcore.work;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.function.Supplier;

/**
 * What a race's settlement does with its works beyond the engine's (Under-Empire: nest/work/ClanUpkeep keeps the slingers
 * armed and the weapon teams raised, runs the Watch-Posts as rooms, and shows the Warlock's Workshop's orders). The
 * settlement hands one to its {@link Works}.
 */
public interface WorksExtension {

    /** Nothing beyond the engine's. */
    WorksExtension NONE = new WorksExtension() {
    };

    /** Works kept beside the rooms' own, by key (a Watch-Post's position): each made the first time its key turns up. */
    default Map<BlockPos, Supplier<RoomWork>> extraRooms(Works works) {
        return Map.of();
    }

    /** Once a second, after the rooms' own work. */
    default void second(Works works, ServerLevel level) {
    }

    /** What else the workshop should keep made, over the rooms' own gear ({@link Works#gear}). */
    default void gear(Works works, ServerLevel level, Map<Crafter.Key, RoomWork.Gear> out) {
    }

    /** The ledger's orders page, if the race shows it its own way (null: the engine's plain one). */
    @Nullable
    default WorkOrders.View view(Works works, ServerLevel level) {
        return null;
    }

    default void save(CompoundTag t, HolderLookup.Provider registries) {
    }

    default void load(CompoundTag t, HolderLookup.Provider registries) {
    }
}
