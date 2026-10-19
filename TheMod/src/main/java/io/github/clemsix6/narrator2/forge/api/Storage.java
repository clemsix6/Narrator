package io.github.clemsix6.narrator2.forge.api;

import java.util.Set;

/**
 * A deployment's small saved state (M5 §4.2): written at once, kept with the world, back after a server restart
 * ({@link ModuleContext#restarted()}). Texts only: {@link Limits#MAX_STORAGE_KEYS} keys of
 * {@link Limits#MAX_STORAGE_KEY_CHARS} characters at most, values of {@link Limits#MAX_STORAGE_VALUE_CHARS}, and
 * {@link Limits#MAX_STORAGE_CHARS} in all; a write beyond throws {@link ForgeRefusal} and changes nothing.
 */
public interface Storage {
    /** The value of {@code key}; null when there is none. */
    String get(String key);

    /** Sets {@code key} to {@code value}; a null value removes the key. */
    void put(String key, String value);

    void remove(String key);

    /** Its keys, in the order they were first put. */
    Set<String> keys();

    /** The value of {@code key} as a whole number; {@code fallback} when there is none or it is not one. */
    int getInt(String key, int fallback);

    void putInt(String key, int value);

    /** The value of {@code key} as a number; {@code fallback} when there is none or it is not one. */
    double getDouble(String key, double fallback);

    void putDouble(String key, double value);
}
