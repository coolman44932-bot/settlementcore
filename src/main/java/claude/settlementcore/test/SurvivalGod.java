package claude.settlementcore.test;

import claude.settlementcore.Core;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.UnknownGameTestException;
import net.minecraft.network.Connection;
import net.minecraft.network.HashedStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A survival player for GameTests, who does things the way a player does: every action goes in as the packet a client sends, through
 * the server's own handler (ServerGamePacketListenerImpl), so the server's checks apply as in a real game: reach, the client having
 * loaded, an unanswered teleport, spawn protection, mayInteract, the block-break timing of ServerPlayerGameMode, the menus' own
 * slot logic, PlayerList#respawn. Nothing calls mod code directly, and there are no shortcuts: no instant mining, no free crafting.
 *
 * <p>Why a mock of its own (the 26.1 traps it gets round):
 * <ul>
 * <li>GameTestHelper#makeMockServerPlayerInLevel's player says CREATIVE whatever its game mode (no wear, no hunger, no mob targets
 * it). This one is an empty anonymous subclass, not ServerPlayer itself: NeoForge's ConfigSync throws on a player of class exactly
 * ServerPlayer it has no config table for. PlayerList#respawn makes a plain ServerPlayer on the same connection, so the
 * connection is never given the config-file channel ({@link MockPlayers#CONFIG_FILE}).
 * <li>Nothing ticks a mock: a real player's connection calls ServerPlayer#doTick (Player#tick: hunger, using an item, picking items
 * up, PlayerTickEvent) once a server tick, and a mock's connection is in no connection list. Here every live god is ticked from
 * LevelTickEvent.Post, as the server's tickConnection does it right after the levels tick. ServerPlayer#tick (the level's entity
 * tick: block breaking, the hurt cooldown) runs as for any player.
 * <li>A joined player takes no damage until the client says it has loaded (ServerGamePacketListenerImpl#hasClientLoaded, 60 ticks at
 * most): the god sends ServerboundPlayerLoadedPacket as it joins and after each respawn, as a client does.
 * <li>The server ignores a client's use-on-block while a teleport is unanswered: every move is answered at once
 * (ServerboundAcceptTeleportationPacket with the server's own id), then a move packet tells the server where the god stands and
 * whether he is on the ground (mining off the ground is 5 times slower) and moves his chunk tickets as a real move does.
 * <li>He is taken off the player list when his test ends, passed, failed or timed out (a listener on the test), and at every
 * batch's end anything left; a test never clears players, and a forgotten survival player is a target for the next tests' mobs.
 * <li>What the server sends him is read off his in-memory channel each tick and dropped (it would pile up for the length of a long
 * test), all but his system chat, which {@link #chat()} keeps.
 * </ul>
 *
 * <p>Positions are the test's own (helper-relative), as GameTestHelper's are. {@link #moveTo} is a teleport, not a walk: the god
 * stands where he is put. Act from the test body or a {@code thenExecute}; wait for a {@link Job} with
 * {@code thenWaitUntil(job::assertDone)}. An action that cannot happen fails the test at once with what stopped it.
 *
 * <p>After {@link #respawn()} the god is a new ServerPlayer object ({@link #player()}): a reference kept from before is the dead one.
 *
 * <p>A survival player can hang a modpack (-Pcompat) run: other mods' hordes and pathfinding go for every survival player
 * (recipes/gametests/a-survival-mock-player-can-hang-a-modpack-run.md).
 */
@EventBusSubscriber(modid = Core.MODID)
public final class SurvivalGod {

    private static final Logger LOG = LogUtils.getLogger();
    private static final List<SurvivalGod> LIVE = new ArrayList<>();
    private static final AtomicInteger NAMES = new AtomicInteger();
    private static final Field AWAITING_TELEPORT = field(ServerGamePacketListenerImpl.class, "awaitingTeleport");
    private static final Field AWAITING_POSITION = field(ServerGamePacketListenerImpl.class, "awaitingPositionFromClient");

    static {
        TestWatchdog.afterEachBatch(level -> leaveAll("its batch ended"));
    }

    private final GameTestHelper h;
    private final ServerGamePacketListenerImpl connection;
    private final EmbeddedChannel channel;
    private final List<Job> jobs = new ArrayList<>();
    private final Deque<String> chat = new ArrayDeque<>();
    /** The client's block-change sequence: every use and break counts one. */
    private int sequence;
    private boolean left;
    private int ticks;
    private @Nullable String lastCraftFailure;

    private SurvivalGod(GameTestHelper h, ServerGamePacketListenerImpl connection, EmbeddedChannel channel) {
        this.h = h;
        this.connection = connection;
        this.channel = channel;
    }

    // ---- joining and leaving

    /**
     * A survival player joins the server and stands at {@code feet} (test-relative; he stands on the block below), facing south. He
     * leaves when the test ends, pass or fail.
     */
    public static SurvivalGod join(GameTestHelper h, BlockPos feet) {
        return join(h, Vec3.atBottomCenterOf(feet));
    }

    /** As {@link #join(GameTestHelper, BlockPos)}, at a test-relative point. */
    public static SurvivalGod join(GameTestHelper h, Vec3 feet) {
        ServerLevel level = h.getLevel();
        MinecraftServer server = level.getServer();
        String name = "survival-god-" + NAMES.incrementAndGet() % 1000;
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        // an empty subclass: NeoForge's ConfigSync throws on a player of class exactly ServerPlayer it has no table for
        ServerPlayer player = new ServerPlayer(server, level, cookie.gameProfile(), cookie.clientInformation()) {
        };
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        player.setGameMode(GameType.SURVIVAL);
        SurvivalGod god = new SurvivalGod(h, player.connection, channel);
        god.noConfigChannel();
        LIVE.add(god);
        h.testInfo.addListener(new Leaves(god));
        god.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());   // the client has loaded the world
        god.moveTo(feet);
        god.drain();
        return god;
    }

    /** Leaves the server (PlayerList#remove), once; the test's end does it by itself. */
    public void leave() {
        LIVE.remove(this);
        if (left) {
            return;
        }
        left = true;
        jobs.clear();
        ServerPlayer p = connection.player;
        MinecraftServer server = p.level().getServer();
        if (server.getPlayerList().getPlayer(p.getUUID()) == p) {
            server.getPlayerList().remove(p);
        }
    }

    public boolean hasLeft() {
        return left;
    }

    /**
     * Takes every god left off the server. The batch's last test is told it is done only after the runner has ended the batch (its
     * own listener on the test comes first), so its god is still here as the batch ends: that one leaves quietly; one whose test is
     * not done is warned of.
     */
    private static void leaveAll(String why) {
        for (SurvivalGod god : List.copyOf(LIVE)) {
            if (!god.h.testInfo.isDone()) {
                LOG.warn("SurvivalGod: {} of {} was still on the server as {}; taken off", god.connection.player.getPlainTextName(),
                        god.h.testInfo.id(), why);
            }
            god.leave();
        }
    }

    /** Takes the god off the server as his test ends, whichever way. */
    private record Leaves(SurvivalGod god) implements GameTestListener {
        @Override
        public void testStructureLoaded(GameTestInfo info) {
        }

        @Override
        public void testPassed(GameTestInfo info, GameTestRunner runner) {
            god.leave();
        }

        @Override
        public void testFailed(GameTestInfo info, GameTestRunner runner) {
            god.leave();
        }

        @Override
        public void testAddedForRerun(GameTestInfo original, GameTestInfo copy, GameTestRunner runner) {
            god.leave();
        }
    }

    // ---- the server's tick for him

    /** What the server's tickConnection does for a real player, right after the levels tick: his doTick; then his jobs go on. */
    @SubscribeEvent
    static void levelTick(LevelTickEvent.Post e) {
        if (!(e.getLevel() instanceof ServerLevel level) || LIVE.isEmpty()) {
            return;
        }
        for (SurvivalGod god : List.copyOf(LIVE)) {
            if (!god.left && god.connection.player.level() == level) {
                god.tick();
            }
        }
    }

    @SubscribeEvent
    static void stopping(ServerStoppingEvent e) {
        LIVE.clear();
    }

    private void tick() {
        ticks++;
        ServerPlayer p = connection.player;
        try {
            p.xo = p.getX();
            p.yo = p.getY();
            p.zo = p.getZ();
            p.doTick();
            drain();
            for (Job job : List.copyOf(jobs)) {
                if (!job.finished()) {
                    job.tick();
                }
                if (job.finished()) {
                    jobs.remove(job);
                }
            }
        } catch (GameTestAssertException ex) {
            h.testInfo.fail(ex);
            leave();
        } catch (RuntimeException ex) {
            // anything else would escape the level tick and end the whole run: fail this test with it instead
            LOG.error("SurvivalGod: {}'s tick threw", p.getPlainTextName(), ex);
            h.testInfo.fail(new UnknownGameTestException(ex));
            leave();
        }
    }

    /** Reads what the server sent him: his system chat is kept, the rest dropped. */
    private void drain() {
        channel.flush();
        Object msg;
        while ((msg = channel.readOutbound()) != null) {
            if (msg instanceof ClientboundSystemChatPacket c && !c.overlay()) {
                chat.addLast(c.content().getString());
                if (chat.size() > 200) {
                    chat.removeFirst();
                }
            }
            ReferenceCountUtil.release(msg);
        }
    }

    // ---- who and where

    /** The god's ServerPlayer: a new object after {@link #respawn()}. */
    public ServerPlayer player() {
        return connection.player;
    }

    public GameTestHelper helper() {
        return h;
    }

    /** Where his feet are, test-relative. */
    public BlockPos at() {
        return h.relativePos(connection.player.blockPosition());
    }

    /** The system chat he was sent (newest last, the last 200). */
    public List<String> chat() {
        drain();
        return List.copyOf(chat);
    }

    /** Whether any chat line he was sent contains {@code text}. */
    public boolean wasTold(String text) {
        return chat().stream().anyMatch(line -> line.contains(text));
    }

    /** Ticks he has been ticked. */
    public int ticks() {
        return ticks;
    }

    // ---- moving and looking

    /**
     * Puts him at {@code feet} (test-relative): a teleport (ServerPlayer#teleportTo), answered as a client answers it, then a move
     * packet from where he now stands, which sets whether he is on the ground and moves his chunk tickets. Not a walk: nothing
     * between here and there is checked. Outside the plot, build him a floor first.
     */
    public SurvivalGod moveTo(BlockPos feet) {
        return moveTo(Vec3.atBottomCenterOf(feet));
    }

    public SurvivalGod moveTo(Vec3 feet) {
        ServerPlayer p = connection.player;
        Vec3 abs = h.absoluteVec(feet);
        p.teleportTo(p.level(), abs.x, abs.y, abs.z, Set.of(), p.getYRot(), p.getXRot(), true);
        answerTeleport();
        sendMove();
        return this;
    }

    /** Turns him to look at {@code at} (test-relative), as a client's look packet does. */
    public SurvivalGod lookAt(Vec3 at) {
        ServerPlayer p = connection.player;
        p.lookAt(EntityAnchorArgument.Anchor.EYES, h.absoluteVec(at));
        connection.handleMovePlayer(new ServerboundMovePlayerPacket.Rot(p.getYRot(), p.getXRot(), onGround(p), false));
        return this;
    }

    public SurvivalGod lookAt(BlockPos at) {
        return lookAt(Vec3.atCenterOf(at));
    }

    private void lookAtAbs(Vec3 abs) {
        lookAt(h.relativeVec(abs));
    }

    private void answerTeleport() {
        try {
            if (AWAITING_POSITION != null && AWAITING_POSITION.get(connection) == null) {
                return;
            }
            int id = AWAITING_TELEPORT == null ? -1 : AWAITING_TELEPORT.getInt(connection);
            if (id < 0) {
                h.fail("SurvivalGod cannot answer the server's teleport: ServerGamePacketListenerImpl.awaitingTeleport is not there (has Minecraft changed?)");
            }
            connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
        } catch (IllegalAccessException ex) {
            h.fail("SurvivalGod cannot answer the server's teleport: " + ex);
        }
    }

    private void sendMove() {
        ServerPlayer p = connection.player;
        connection.handleMovePlayer(new ServerboundMovePlayerPacket.PosRot(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), onGround(p), false));
    }

    private static boolean onGround(ServerPlayer p) {
        AABB box = p.getBoundingBox();
        return !p.level().noCollision(p, new AABB(box.minX, box.minY - 0.05, box.minZ, box.maxX, box.minY, box.maxZ));
    }

    // ---- the inventory

    public Inventory inventory() {
        return connection.player.getInventory();
    }

    /** Puts {@code stack} in his inventory (what /give does); it must all fit. */
    public SurvivalGod give(ItemStack stack) {
        ItemStack copy = stack.copy();
        boolean all = connection.player.getInventory().add(copy) && copy.isEmpty();
        h.assertTrue(all, "SurvivalGod: no room in the inventory for " + stack + " (" + copy.getCount() + " left over)");
        return this;
    }

    public SurvivalGod give(ItemLike item, int count) {
        return give(new ItemStack(item, count));
    }

    /** How many of {@code item} he carries (every slot, armour and off hand too). */
    public int count(ItemLike item) {
        return connection.player.getInventory().countItem(item.asItem());
    }

    /** What is in his main hand. */
    public ItemStack held() {
        return connection.player.getMainHandItem();
    }

    /** Selects hotbar slot {@code slot} (0-8), as the client's scroll or number key does. */
    public SurvivalGod select(int slot) {
        h.assertTrue(slot >= 0 && slot < Inventory.getSelectionSize(), "SurvivalGod: no hotbar slot " + slot);
        connection.handleSetCarriedItem(new ServerboundSetCarriedItemPacket(slot));
        return this;
    }

    /**
     * Takes {@code item} in his main hand: selects the hotbar slot that has it, or first swaps it into the selected hotbar slot from
     * the main inventory (a number key over the slot, in the inventory screen). Fails if he has none.
     */
    public SurvivalGod hold(ItemLike item) {
        Inventory inv = inventory();
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inv.getItem(i).is(item.asItem())) {
                return select(i);
            }
        }
        for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE; i++) {
            if (inv.getItem(i).is(item.asItem())) {
                AbstractContainerMenu menu = connection.player.containerMenu;
                int slot = menuSlot(menu, i);
                h.assertTrue(slot >= 0, "SurvivalGod: the open menu " + menu + " has no slot for inventory slot " + i);
                click(menu, slot, inv.getSelectedSlot(), ContainerInput.SWAP);
                return this;
            }
        }
        h.fail("SurvivalGod has no " + item.asItem() + " to hold (inventory: " + contents() + ")");
        return this;
    }

    /** His inventory in one line, for failure messages. */
    public String contents() {
        Inventory inv = inventory();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) {
                out.add(i + ":" + inv.getItem(i));
            }
        }
        return out.isEmpty() ? "empty" : String.join(", ", out);
    }

    // ---- hands

    /**
     * Right-clicks face {@code face} of block {@code pos} (test-relative) with what he holds: a block placed, a door opened, a menu
     * opened, a bucket filled, a modded block's own use. The item is used up as in survival. He looks at the face first.
     */
    public SurvivalGod useOn(BlockPos pos, Direction face) {
        BlockPos abs = h.absolutePos(pos);
        reach(abs, "use");
        Vec3 hit = Vec3.atCenterOf(abs).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        lookAtAbs(hit);
        connection.handleUseItemOn(new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, abs, false), ++sequence));
        return this;
    }

    /**
     * Places the block he holds against face {@code face} of block {@code pos} (test-relative), as a right-click does; fails if he
     * holds no block or nothing was placed. Returns where it went (test-relative).
     */
    public BlockPos placeFromHotbar(BlockPos pos, Direction face) {
        ItemStack held = held();
        h.assertTrue(held.getItem() instanceof BlockItem, "SurvivalGod holds " + held + ", not a block, to place against " + pos + " " + face);
        Item item = held.getItem();
        int before = count(item);
        BlockPos target = h.getBlockState(pos).canBeReplaced() ? pos : pos.relative(face);
        BlockState was = h.getBlockState(target);
        useOn(pos, face);
        BlockState now = h.getBlockState(target);
        h.assertTrue(now != was, "SurvivalGod could not place " + item + " against " + pos + " " + face + ": " + target + " is still " + now
                + "; he holds " + held());
        return target;
    }

    /** Right-clicks the air with what he holds (eating starts this way, a bow draws, a potion is drunk). */
    public SurvivalGod useItem() {
        ServerPlayer p = connection.player;
        connection.handleUseItem(new ServerboundUseItemPacket(InteractionHand.MAIN_HAND, ++sequence, p.getYRot(), p.getXRot()));
        return this;
    }

    /** Right-clicks entity {@code e} with what he holds (feeding, leashing, trading, a mod's own interaction). */
    public SurvivalGod useOnEntity(Entity e) {
        ServerPlayer p = connection.player;
        lookAtAbs(e.getBoundingBox().getCenter());
        h.assertTrue(p.isWithinEntityInteractionRange(e.getBoundingBox(), 3.0), "SurvivalGod at " + p.position() + " cannot reach " + e + " to use something on it");
        connection.handleInteract(new ServerboundInteractPacket(e.getId(), InteractionHand.MAIN_HAND, new Vec3(0, e.getBbHeight() / 2, 0), false));
        return this;
    }

    /** Hits entity {@code e} with what he holds (the attack's charge is however far it has come back since his last swing). */
    public SurvivalGod attack(Entity e) {
        ServerPlayer p = connection.player;
        lookAtAbs(e.getBoundingBox().getCenter());
        h.assertTrue(p.isWithinAttackRange(p.getMainHandItem(), e.getBoundingBox(), 3.0), "SurvivalGod at " + p.position() + " cannot reach " + e + " to hit it");
        connection.handleAttack(new ServerboundAttackPacket(e.getId()));
        return this;
    }

    private void reach(BlockPos abs, String what) {
        ServerPlayer p = connection.player;
        h.assertTrue(p.isWithinBlockInteractionRange(abs, 1.0), "SurvivalGod at " + h.relativeVec(p.position()) + " cannot reach "
                + h.relativePos(abs) + " to " + what + " it (reach " + p.blockInteractionRange() + "): move him closer");
    }

    // ---- mining

    /**
     * Mines block {@code pos} (test-relative) with what he holds, as a player holding the button down: START_DESTROY_BLOCK, the
     * break's own pace (BlockState#getDestroyProgress for him, his tool and his footing, each tick, as the client adds it up), then
     * STOP_DESTROY_BLOCK, which ServerPlayerGameMode checks against its own count. Stone by hand takes 150 ticks and drops nothing;
     * the tool wears. Wait on the job; it fails if the block cannot be broken at all.
     */
    public Job mine(BlockPos pos) {
        BlockPos abs = h.absolutePos(pos);
        ServerLevel level = h.getLevel();
        BlockState state = level.getBlockState(abs);
        h.assertTrue(!state.isAir(), "SurvivalGod: nothing to mine at " + pos);
        reach(abs, "mine");
        ServerPlayer p = connection.player;
        Direction face = Direction.getApproximateNearest(p.getEyePosition().subtract(Vec3.atCenterOf(abs)));
        lookAtAbs(Vec3.atCenterOf(abs).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5));
        Mining job = new Mining(pos, abs, face, state);
        connection.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, abs, face, ++sequence));
        return start(job);
    }

    private final class Mining extends Job {
        final BlockPos abs;
        final Direction face;
        final BlockState state;
        float progress;
        boolean stopped;

        Mining(BlockPos pos, BlockPos abs, Direction face, BlockState state) {
            super("mine " + state.getBlock() + " at " + pos);
            this.abs = abs;
            this.face = face;
            this.state = state;
        }

        @Override
        void tick() {
            super.tick();
            ServerPlayer p = connection.player;
            BlockState now = p.level().getBlockState(abs);
            if (now != state) {
                done("broken in " + ticks + " ticks (now " + now + ")");
                return;
            }
            if (!p.isAlive()) {
                fail("he died mining it");
                return;
            }
            if (!stopped) {
                float step = state.getDestroyProgress(p, p.level(), abs);
                if (step <= 0) {
                    fail(state + " cannot be broken by him with " + p.getMainHandItem() + " (destroy progress 0)");
                    return;
                }
                progress += step;
                status = "progress " + Math.round(progress * 100) + "% after " + ticks + " ticks with " + p.getMainHandItem();
                if (progress >= 1.0F) {
                    stopped = true;
                    connection.handlePlayerAction(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, abs, face, ++sequence));
                }
            } else if (ticks > 400) {
                fail("the server never broke it after the client's STOP (" + status + ")");
            }
        }
    }

    // ---- eating

    /** Eats what he holds: a right-click held for the food's whole use time (ServerPlayerGameMode#useItem, then LivingEntity's use ticks). */
    public Job eat() {
        ServerPlayer p = connection.player;
        ItemStack food = held();
        h.assertTrue(!food.isEmpty(), "SurvivalGod has nothing in hand to eat");
        int before = p.getFoodData().getFoodLevel();
        String what = food.toString();
        useItem();
        Job job = new Job("eat " + what) {
            @Override
            void tick() {
                super.tick();
                if (connection.player.isUsingItem()) {
                    status = "eating, " + connection.player.getUseItemRemainingTicks() + " ticks to go";
                    return;
                }
                int now = connection.player.getFoodData().getFoodLevel();
                if (now > before) {
                    done("food " + before + " -> " + now);
                } else if (ticks <= 1) {
                    fail("he did not start eating " + what + " (food " + before + "; a full player cannot eat most food)");
                } else {
                    fail("he stopped eating " + what + " with food still " + now);
                }
            }
        };
        return start(job);
    }

    /** His food level (0-20). */
    public int food() {
        return connection.player.getFoodData().getFoodLevel();
    }

    /** Sets his hunger: set-up for a test, standing for the time it takes to get there. */
    public SurvivalGod hunger(int food, float saturation) {
        connection.player.getFoodData().setFoodLevel(food);
        connection.player.getFoodData().setSaturation(saturation);
        return this;
    }

    public float health() {
        return connection.player.getHealth();
    }

    // ---- picking up

    /**
     * Picks up the items lying within {@code radius} blocks: he goes to each in turn (a teleport) and his own tick picks it up
     * (Player#aiStep touching it, ItemEntity#playerTouch: its pickup delay and his room count). Done when none of them lies there;
     * one he could not take in 100 ticks fails the job.
     */
    public Job pickUp(double radius) {
        ServerPlayer p = connection.player;
        List<ItemEntity> items = new ArrayList<>(p.level().getEntitiesOfClass(ItemEntity.class, p.getBoundingBox().inflate(radius), ItemEntity::isAlive));
        Job job = new Job("pick up " + items.size() + " item stack(s) within " + radius) {
            @Nullable ItemEntity target;
            int since;

            @Override
            void tick() {
                super.tick();
                if (target != null && target.isAlive() && ++since > 100) {
                    fail(target.getItem() + " at " + h.relativeVec(target.position()) + " was not picked up in 100 ticks (pickup delay "
                            + target.hasPickUpDelay() + ", inventory " + contents() + ")");
                    return;
                }
                if (target == null || !target.isAlive()) {
                    items.removeIf(i -> !i.isAlive());
                    if (items.isEmpty()) {
                        done("all picked up in " + ticks + " ticks");
                        return;
                    }
                    target = items.removeFirst();
                    since = 0;
                    moveTo(h.relativeVec(target.position()));
                    status = "going for " + target.getItem();
                }
            }
        };
        return start(job);
    }

    // ---- crafting

    /**
     * Crafts once at the crafting table at {@code table} (test-relative): right-clicks it open, puts the items into the 3 x 3 grid
     * through the menu's own clicks (picks a stack up, right-clicks one into the cell, puts the rest back), takes the result onto
     * the cursor and puts it in his inventory, and closes the table (what is left in the grid comes back). {@code grid} is row by
     * row, null for an empty cell, up to 9. Fails the test with what went wrong when nothing is made; {@link #tryCraft} does not.
     */
    public ItemStack craft(BlockPos table, @Nullable ItemLike... grid) {
        ItemStack made = tryCraft(table, grid);
        h.assertTrue(!made.isEmpty(), "SurvivalGod could not craft at " + table + ": " + lastCraftFailure);
        return made;
    }

    /** As {@link #craft}, but hands back an empty stack when nothing could be made ({@link #lastCraftFailure()} says why). */
    public ItemStack tryCraft(BlockPos table, @Nullable ItemLike... grid) {
        h.assertTrue(grid.length <= 9, "a crafting table's grid has 9 cells, not " + grid.length);
        useOn(table, Direction.UP);
        AbstractContainerMenu menu = connection.player.containerMenu;
        if (!(menu instanceof CraftingMenu crafting)) {
            lastCraftFailure = "right-clicking " + h.getBlockState(table) + " at " + table + " opened " + menu + ", not a crafting table";
            return ItemStack.EMPTY;
        }
        return craftIn(crafting, 3, grid);
    }

    /** Crafts once in his own 2 x 2 grid (the inventory screen); {@code grid} row by row, up to 4. Fails the test when nothing is made. */
    public ItemStack craftInInventory(@Nullable ItemLike... grid) {
        ItemStack made = tryCraftInInventory(grid);
        h.assertTrue(!made.isEmpty(), "SurvivalGod could not craft in his inventory: " + lastCraftFailure);
        return made;
    }

    public ItemStack tryCraftInInventory(@Nullable ItemLike... grid) {
        h.assertTrue(grid.length <= 4, "the inventory's grid has 4 cells, not " + grid.length);
        ServerPlayer p = connection.player;
        if (p.containerMenu != p.inventoryMenu) {
            closeMenu();
        }
        return craftIn(p.inventoryMenu, 2, grid);
    }

    /** Why the last craft made nothing. */
    public @Nullable String lastCraftFailure() {
        return lastCraftFailure;
    }

    private ItemStack craftIn(AbstractCraftingMenu menu, int width, @Nullable ItemLike[] grid) {
        lastCraftFailure = null;
        List<Slot> cells = menu.getInputGridSlots();
        for (int i = 0; i < grid.length; i++) {
            if (grid[i] == null) {
                continue;
            }
            Item item = grid[i].asItem();
            int inv = inventorySlotWith(item);
            if (inv < 0) {
                lastCraftFailure = "he has no " + item + " for cell " + i + " of " + Arrays.toString(grid) + " (inventory: " + contents() + ")";
                closeMenu();
                return ItemStack.EMPTY;
            }
            int from = menuSlot(menu, inv);
            int cell = cells.get(i).index;
            click(menu, from, 0, ContainerInput.PICKUP);     // the stack onto the cursor
            click(menu, cell, 1, ContainerInput.PICKUP);     // one into the cell
            click(menu, from, 0, ContainerInput.PICKUP);     // the rest back
        }
        ItemStack result = menu.getResultSlot().getItem().copy();
        if (result.isEmpty()) {
            lastCraftFailure = "the " + width + " x " + width + " grid " + Arrays.toString(grid) + " makes nothing";
            closeMenu();
            return ItemStack.EMPTY;
        }
        click(menu, menu.getResultSlot().index, 0, ContainerInput.PICKUP);   // crafts once: the result onto the cursor
        ItemStack carried = menu.getCarried();
        if (!carried.isEmpty()) {
            int to = roomFor(carried);
            if (to >= 0) {
                click(menu, menuSlot(menu, to), 0, ContainerInput.PICKUP);
            }
        }
        closeMenu();                                                          // the grid's leftovers, and a full cursor, come back
        return result;
    }

    /** Closes his open menu, as a client closing its screen (ServerboundContainerClosePacket). */
    public SurvivalGod closeMenu() {
        connection.handleContainerClose(new ServerboundContainerClosePacket(connection.player.containerMenu.containerId));
        return this;
    }

    private void click(AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        connection.handleContainerClick(new ServerboundContainerClickPacket(menu.containerId, menu.getStateId(), (short) slot, (byte) button, input,
                new Int2ObjectOpenHashMap<>(), HashedStack.EMPTY));
    }

    /** The menu's slot index for inventory slot {@code inv}, or -1. */
    private int menuSlot(AbstractContainerMenu menu, int inv) {
        for (Slot s : menu.slots) {
            if (s.container == connection.player.getInventory() && s.getContainerSlot() == inv) {
                return s.index;
            }
        }
        return -1;
    }

    /** An inventory slot (hotbar or main) holding {@code item}, the fullest first; -1 if none. */
    private int inventorySlotWith(Item item) {
        Inventory inv = inventory();
        int best = -1;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (inv.getItem(i).is(item) && (best < 0 || inv.getItem(i).getCount() > inv.getItem(best).getCount())) {
                best = i;
            }
        }
        return best;
    }

    /** An inventory slot that can take {@code stack}: one of the same with room first, else an empty one; -1 if none. */
    private int roomFor(ItemStack stack) {
        Inventory inv = inventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (ItemStack.isSameItemSameComponents(s, stack) && s.getCount() < s.getMaxStackSize()) {
                return i;
            }
        }
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (inv.getItem(i).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    // ---- dying and coming back

    /** Dies of {@code source}: a hit of everything he has, through the ordinary damage path. Fails if it does not kill him. */
    public SurvivalGod die(DamageSource source) {
        ServerPlayer p = connection.player;
        p.hurtServer(p.level(), source, Float.MAX_VALUE);
        h.assertTrue(p.isDeadOrDying(), "SurvivalGod did not die of " + source.getMsgId() + ": health " + p.getHealth() + " (invulnerable? "
                + p.isInvulnerableTo(p.level(), source) + ", client loaded " + connection.hasClientLoaded() + ")");
        drain();
        return this;
    }

    public SurvivalGod die() {
        return die(connection.player.level().damageSources().generic());
    }

    /**
     * Presses Respawn on the death screen (ServerboundClientCommandPacket PERFORM_RESPAWN): PlayerList#respawn makes a new
     * ServerPlayer at his bed or the world's spawn, keeping his things only as keep_inventory says. Returns the new player.
     */
    public ServerPlayer respawn() {
        ServerPlayer dead = connection.player;
        h.assertTrue(dead.isDeadOrDying(), "SurvivalGod cannot respawn: he is alive (health " + dead.getHealth() + ")");
        connection.handleClientCommand(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
        ServerPlayer now = connection.player;
        h.assertTrue(now != dead, "SurvivalGod pressed Respawn and is still the dead player");
        noConfigChannel();
        connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
        answerTeleport();
        sendMove();
        drain();
        return now;
    }

    private void noConfigChannel() {
        ChannelAttributes.getOrCreateAdHocChannels(connection.getConnection()).remove(MockPlayers.CONFIG_FILE);
    }

    // ---- jobs

    private <J extends Job> J start(J job) {
        jobs.add(job);
        return job;
    }

    /**
     * Something he does over ticks (mining, eating, picking up). Wait on it with {@code thenWaitUntil(job::assertDone)}; a job that
     * cannot finish fails the test at once, with what stopped it.
     */
    public abstract class Job {
        final String what;
        int ticks;
        String status = "not started";
        private boolean done;
        private @Nullable String failure;

        Job(String what) {
            this.what = what;
        }

        void tick() {
            ticks++;
        }

        final void done(String how) {
            done = true;
            status = how;
        }

        final void fail(String why) {
            failure = why;
            status = "failed: " + why;
            h.testInfo.fail(new GameTestAssertException(Component.literal("SurvivalGod could not " + what + ": " + why), (int) h.getTick()));
        }

        final boolean finished() {
            return done || failure != null || left;
        }

        public boolean isDone() {
            return done;
        }

        public boolean failed() {
            return failure != null;
        }

        /** How it went, so far. */
        public String status() {
            return what + ": " + status;
        }

        /** Ticks it has run. */
        public int ticks() {
            return ticks;
        }

        /** Passes once the job is done; for {@code thenWaitUntil}. */
        public void assertDone() {
            h.assertTrue(done, "SurvivalGod still at " + status());
        }
    }

    private static @Nullable Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            LOG.error("SurvivalGod: cannot reach {}.{} (has Minecraft changed?)", owner.getSimpleName(), name, ex);
            return null;
        }
    }
}
