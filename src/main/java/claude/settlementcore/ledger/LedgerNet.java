package claude.settlementcore.ledger;

import claude.settlementcore.Core;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.List;

/**
 * What the Ledger says over the wire ({@link LedgerMenu}): the tally down to the screen, and the clicks on its grid back up.
 * The engine's only payloads, registered on its own mod bus.
 *
 * <p>A click never carries an item. It names a line of a tally the server sent ({@link LedgerAct#serial}, {@link LedgerAct#index}),
 * and the server takes the item from its own copy of that tally: a full shulker box in the stores would not fit a packet to
 * the server (32767 bytes at most), and nothing a client says is taken for an item.
 */
public final class LedgerNet {

    private LedgerNet() {
    }

    /**
     * Server to client: the settlement's stores for the Ledger open as {@code containerId}. {@code serial} numbers this tally (a click
     * names it), {@code more} is how many kinds would not fit the packet and were left out ({@link LedgerMenu#LINE_BUDGET}).
     */
    public record LedgerData(int containerId, int serial, List<SettlementLedger.Line> lines, int more, SettlementLedger.Summary summary,
                             List<HaulLog.Flow> flows) implements CustomPacketPayload {
        public static final Type<LedgerData> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Core.MODID, "ledger"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LedgerData> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, LedgerData::containerId,
                ByteBufCodecs.VAR_INT, LedgerData::serial,
                SettlementLedger.Line.STREAM_CODEC.apply(ByteBufCodecs.list()), LedgerData::lines,
                ByteBufCodecs.VAR_INT, LedgerData::more,
                SettlementLedger.Summary.STREAM_CODEC, LedgerData::summary,
                HaulLog.Flow.STREAM_CODEC.apply(ByteBufCodecs.list()), LedgerData::flows,
                LedgerData::new);

        @Override
        public Type<LedgerData> type() {
            return TYPE;
        }
    }

    /**
     * Client to server: a click on the Ledger's grid ({@link LedgerMenu#TAKE}, {@link LedgerMenu#TAKE_TO_INVENTORY},
     * {@link LedgerMenu#PUT_CARRIED}). A take names line {@code index} of tally {@code serial}; a put names neither (it puts what
     * the player carries). {@code amount} is how many.
     */
    public record LedgerAct(int containerId, int action, int serial, int index, int amount) implements CustomPacketPayload {
        public static final Type<LedgerAct> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Core.MODID, "ledger_act"));
        public static final StreamCodec<RegistryFriendlyByteBuf, LedgerAct> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, LedgerAct::containerId,
                ByteBufCodecs.VAR_INT, LedgerAct::action,
                ByteBufCodecs.VAR_INT, LedgerAct::serial,
                ByteBufCodecs.VAR_INT, LedgerAct::index,
                ByteBufCodecs.VAR_INT, LedgerAct::amount,
                LedgerAct::new);

        @Override
        public Type<LedgerAct> type() {
            return TYPE;
        }
    }

    /** From Settlement Core's constructor, once. */
    public static void register(IEventBus modBus) {
        modBus.addListener(RegisterPayloadHandlersEvent.class, LedgerNet::payloads);
    }

    private static void payloads(RegisterPayloadHandlersEvent e) {
        var r = e.registrar("1");
        // the client's handler touches the player's open menu and nothing of the client's own, so a server loads no screen by it
        r.playToClient(LedgerData.TYPE, LedgerData.CODEC, (msg, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player().containerMenu instanceof LedgerMenu m && m.containerId == msg.containerId()) {
                m.receive(msg);
            }
        }));
        r.playToServer(LedgerAct.TYPE, LedgerAct.CODEC, (msg, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp && sp.containerMenu instanceof LedgerMenu m && m.containerId == msg.containerId()) {
                m.click(sp, msg.action(), msg.serial(), msg.index(), msg.amount());
            }
        }));
    }
}
