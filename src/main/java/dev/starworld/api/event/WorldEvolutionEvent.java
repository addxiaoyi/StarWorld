package dev.starworld.api.event;

import dev.starworld.domain.WorldState.EventState;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Committed event transition; current=null means the long-term aftermath has ended. */
public final class WorldEvolutionEvent extends Event {
    private static final HandlerList HANDLERS=new HandlerList();
    private final UUID cityId; private final EventState previous,current;
    public WorldEvolutionEvent(UUID cityId,EventState previous,EventState current){this.cityId=cityId;this.previous=previous;this.current=current;}
    public UUID cityId(){return cityId;} public EventState previous(){return previous;}public EventState current(){return current;}
    @Override public HandlerList getHandlers(){return HANDLERS;}
    public static HandlerList getHandlerList(){return HANDLERS;}
}
