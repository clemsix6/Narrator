package io.github.clemsix6.narrator2.forge.api;

/**
 * A creature as it is now ({@link World#creatures}, {@link Creature#info()}).
 *
 * @param uuid its UUID, as text: the key of {@link Creatures#get} and {@link Creatures#claim}.
 * @param type its type id ({@code zombie}).
 * @param at where its feet are.
 * @param name its custom name; null when it has none.
 * @param own one of this deployment's creatures.
 */
public record CreatureInfo(String uuid, String type, Place at, float health, float maxHealth, String name, boolean own) {}
