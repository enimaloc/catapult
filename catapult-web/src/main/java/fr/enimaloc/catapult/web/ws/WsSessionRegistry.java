package fr.enimaloc.catapult.web.ws;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WsSessionRegistry {

    private final Map<String, WsSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> subscribersByChannel = new ConcurrentHashMap<>();

    public void add(WsSession s) {
        sessions.put(s.id(), s);
    }

    public void remove(String sessionId) {
        WsSession s = sessions.remove(sessionId);
        if (s == null) return;
        for (String channel : s.subscriptions()) {
            var set = subscribersByChannel.get(channel);
            if (set != null) {
                set.remove(sessionId);
                if (set.isEmpty()) subscribersByChannel.remove(channel);
            }
        }
    }

    public void subscribe(String sessionId, String channel) {
        WsSession s = sessions.get(sessionId);
        if (s == null) return;
        s.subscriptions().add(channel);
        subscribersByChannel.computeIfAbsent(channel, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    public void unsubscribe(String sessionId, String channel) {
        WsSession s = sessions.get(sessionId);
        if (s == null) return;
        s.subscriptions().remove(channel);
        var set = subscribersByChannel.get(channel);
        if (set != null) {
            set.remove(sessionId);
            if (set.isEmpty()) subscribersByChannel.remove(channel);
        }
    }

    public Collection<WsSession> subscribersOf(String channel) {
        var ids = subscribersByChannel.getOrDefault(channel, Collections.emptySet());
        return ids.stream().map(sessions::get).filter(Objects::nonNull).toList();
    }

    public Collection<WsSession> allSessions() {
        return List.copyOf(sessions.values());
    }

    public int size() {
        return sessions.size();
    }

    public WsSession get(String sessionId) {
        return sessions.get(sessionId);
    }
}
