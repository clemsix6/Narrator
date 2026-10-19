package io.github.clemsix6.narrator2.forge.api;

/**
 * An online player as they are now ({@link World}).
 *
 * @param name as the game shows it.
 * @param uuid their UUID, as text.
 * @param at where their feet are.
 * @param health 0 to {@code maxHealth} (20 is ten hearts).
 * @param food 0 to 20.
 * @param gameMode {@code survival}, {@code creative} or {@code adventure} (spectators are left out).
 * @param onGround standing on a block.
 * @param yaw where they look, horizontally, in degrees: 0 south, 90 west, 180 north, -90 east.
 * @param pitch where they look, vertically, in degrees: -90 straight up, 0 ahead, 90 straight down.
 * @param heldItem the item id in their main hand; {@code air} when it is empty.
 * @param underground no sky light where they are, and well below sea level: in a cave or a mine, not under a roof
 *     (the Narrator's own notion, read once a second).
 */
public record PlayerInfo(
    String name,
    String uuid,
    Place at,
    float health,
    float maxHealth,
    int food,
    String gameMode,
    boolean onGround,
    boolean sneaking,
    boolean sprinting,
    boolean swimming,
    boolean gliding,
    float yaw,
    float pitch,
    String heldItem,
    boolean underground
) {}
