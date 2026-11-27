package fr.enimaloc.catapult.service.title;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One title send at a time per channel: a category change (its title read, sent, then remembered) and the Guest Star
 * refresh would otherwise interleave, Twitch ending with the older title and the streamer's own one mistaken.
 * Reentrant; a channel's lock is kept for the life of the process (one small object per channel).
 */
public final class TitleSendLocks {

    private static final Map<UUID, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private TitleSendLocks() {
    }

    /** {@code send} run while no other title send of the channel {@code userId} runs. */
    public static <T> T exclusively(UUID userId, Supplier<T> send) {
        if (userId == null) return send.get();
        ReentrantLock lock = LOCKS.computeIfAbsent(userId, id -> new ReentrantLock());
        lock.lock();
        try {
            return send.get();
        } finally {
            lock.unlock();
        }
    }
}
