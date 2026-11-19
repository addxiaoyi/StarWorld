package dev.starworld.api.event;

import dev.starworld.domain.WorldState.Settlement;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Notification after the SQLite commit, dispatched on the Paper server thread. Cannot change settlement. */
public final class IndustrySettledEvent extends Event {
    private static final HandlerList HANDLERS=new HandlerList();
    private final Settlement settlement;
    public IndustrySettledEvent(Settlement settlement) {this.settlement=settlement;}
    public Settlement settlement(){return settlement;}
    @Override public HandlerList getHandlers(){return HANDLERS;}
    public static HandlerList getHandlerList(){return HANDLERS;}
}
