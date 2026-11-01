package claude.settlementcore.testrace;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The test race's registry entries: the Heart (block, item, block entity), the Worker and the Guard (and their eggs), and a tab. */
public final class TRRegistry {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(TestRace.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(TestRace.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TestRace.MODID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, TestRace.MODID);
    private static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, TestRace.MODID);

    public static final DeferredBlock<HeartBlock> HEART = BLOCKS.registerBlock("heart", HeartBlock::new,
            p -> p.mapColor(MapColor.COLOR_LIGHT_GREEN).strength(3f, 6f).sound(SoundType.AMETHYST).lightLevel(s -> 9));
    public static final DeferredItem<BlockItem> HEART_ITEM = ITEMS.registerSimpleBlockItem("heart", HEART, Item.Properties::new);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HeartBlockEntity>> HEART_ENTITY = BLOCK_ENTITIES.register("heart",
            () -> new BlockEntityType<>(HeartBlockEntity::new, HEART.get()));

    /** About 1.5 blocks tall: a small worker. */
    public static final DeferredHolder<EntityType<?>, EntityType<Worker>> WORKER = ENTITIES.register("worker",
            () -> EntityType.Builder.of(Worker::new, MobCategory.CREATURE).sized(0.6f, 1.5f).eyeHeight(1.3f).clientTrackingRange(10)
                    .build(key("worker")));
    /** About 1.8 blocks tall: the worker's armoured kin. */
    public static final DeferredHolder<EntityType<?>, EntityType<Guard>> GUARD = ENTITIES.register("guard",
            () -> EntityType.Builder.of(Guard::new, MobCategory.CREATURE).sized(0.7f, 1.8f).eyeHeight(1.6f).clientTrackingRange(10)
                    .build(key("guard")));

    public static final DeferredItem<SpawnEggItem> WORKER_EGG = ITEMS.registerItem("worker_spawn_egg",
            p -> new SpawnEggItem(p.spawnEgg(WORKER.get())));
    public static final DeferredItem<SpawnEggItem> GUARD_EGG = ITEMS.registerItem("guard_spawn_egg",
            p -> new SpawnEggItem(p.spawnEgg(GUARD.get())));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> TAB = TABS.register("testrace", () -> CreativeModeTab.builder()
            .title(Component.literal("Settlement Core Test Race"))
            .icon(() -> HEART_ITEM.get().getDefaultInstance())
            .displayItems((params, out) -> {
                out.accept(HEART_ITEM.get());
                out.accept(WORKER_EGG.get());
                out.accept(GUARD_EGG.get());
            })
            .build());

    private TRRegistry() {
    }

    private static ResourceKey<EntityType<?>> key(String name) {
        return ResourceKey.create(Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(TestRace.MODID, name));
    }

    static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        ENTITIES.register(modBus);
        TABS.register(modBus);
        modBus.addListener(TRRegistry::attributes);
    }

    private static void attributes(EntityAttributeCreationEvent e) {
        e.put(WORKER.get(), Worker.createAttributes().build());
        e.put(GUARD.get(), Guard.createAttributes().build());
    }
}
