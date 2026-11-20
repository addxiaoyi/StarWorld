package net.momirealms.craftengine.core.item;

import net.momirealms.craftengine.core.entity.player.Player;

/** Test-only CE public building contract. */
@FunctionalInterface
public interface BuildableItem { Item buildItem(Player player); }
