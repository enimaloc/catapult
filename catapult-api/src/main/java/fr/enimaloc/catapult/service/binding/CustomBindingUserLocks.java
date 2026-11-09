package fr.enimaloc.catapult.service.binding;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One lock per streamer around what decides their active custom binding: a manual choice and the scheduler each
 * apply a category on Twitch then record it, and neither may record in the middle of the other. Reentrant: the
 * scheduler activates through the same service the manual choices go through.
 */
@Component
public class CustomBindingUserLocks {

    private final Map<UUID, ReentrantLock> locks = new ConcurrentHashMap<>();

    public <T> T call(UUID userId, Supplier<T> task) {
        ReentrantLock lock = locks.computeIfAbsent(userId, id -> new ReentrantLock());
        lock.lock();
        try {
            return task.get();
        } finally {
            lock.unlock();
        }
    }

    public void run(UUID userId, Runnable task) {
        call(userId, () -> {
            task.run();
            return null;
        });
    }
}
