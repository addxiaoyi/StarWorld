package dev.starworld.api.event;

import dev.starworld.evolution.EvolutionState.Profile;
import org.bukkit.event.*;

/** Postcommit notification. The profile is immutable and never authorizes a StarCore mutation. */
public final class CityDevelopmentEvent extends Event {
    private static final HandlerList HANDLERS=new HandlerList();
    private final Profile profile;
    public CityDevelopmentEvent(Profile profile){this.profile=profile;}
    public Profile profile(){return profile;}
    @Override public HandlerList getHandlers(){return HANDLERS;}
    public static HandlerList getHandlerList(){return HANDLERS;}
}
