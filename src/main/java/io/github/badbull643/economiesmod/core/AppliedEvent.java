package io.github.badbull643.economiesmod.core;

/**
 * An event that has just been applied to local state, and how it got here.
 *
 * Carries three things the callback used to lose:
 *
 *  - the {@link EventApplier.Result}, and with it the fills. These are computed
 *    anyway when the event is applied, including fills where this player was the
 *    resting side — which is the only place that information exists on a client.
 *
 *  - whether this is <em>live</em>. Replaying a history and receiving a broadcast
 *    look identical to anything downstream of apply, and they must not be treated
 *    alike: a handler that hands over real items would do so again for every
 *    historical event of yours that a sync replays.
 */
public final class AppliedEvent {

    public final SequencedEvent event;
    public final EventApplier.Result result;

    /**
     * False when this event is being replayed from a history rather than arriving
     * as it happens.
     *
     * Anything with an effect outside the ledger — giving a player items, sending a
     * notification — must check this. Anything that only reads state can ignore it,
     * because a replayed event moves the ledger exactly as the live one did.
     */
    public final boolean live;

    public AppliedEvent(SequencedEvent event, EventApplier.Result result, boolean live) {
        this.event = event;
        this.result = result;
        this.live = live;
    }
}
