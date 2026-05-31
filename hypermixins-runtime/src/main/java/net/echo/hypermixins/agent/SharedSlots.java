package net.echo.hypermixins.agent;

import net.echo.hypermixins.annotations.Ref;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Backing store for {@code @Share(key)} handler parameters. Each distinct key resolves to one
 * shared {@link Ref} for the lifetime of this JVM; concurrent handlers reading and writing the
 * same key see one another's updates. Generated transformer code calls
 * {@link #acquire(String)} once per matched site and pushes the result as the handler arg.
 */
public final class SharedSlots {

    private static final ConcurrentHashMap<String, Ref<Object>> SHARED = new ConcurrentHashMap<>();

    private SharedSlots() {}

    /** Returns the (lazily allocated) shared {@link Ref} bound to {@code key}. */
    public static Ref<Object> acquire(String key) {
        return SHARED.computeIfAbsent(key, _ -> Ref.empty());
    }

    /** Clears every shared slot. Tests only. */
    public static void clearForTests() {
        SHARED.clear();
    }
}
