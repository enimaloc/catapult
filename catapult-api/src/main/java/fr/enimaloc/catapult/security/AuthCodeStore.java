package fr.enimaloc.catapult.security;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthCodeStore {

    private record Entry(String jwt, long expiresAt) {}

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    public String issue(String jwt) {
        String code = UUID.randomUUID().toString();
        store.put(code, new Entry(jwt, System.currentTimeMillis() + 30_000));
        return code;
    }

    public Optional<String> consume(String code) {
        Entry entry = store.remove(code);
        if (entry == null || System.currentTimeMillis() > entry.expiresAt()) return Optional.empty();
        return Optional.of(entry.jwt());
    }

    @Scheduled(fixedDelay = 60_000)
    void cleanup() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now > e.getValue().expiresAt());
    }
}
