package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.GameBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class StreamStateService {

    private final Map<UUID, Boolean> liveStatus = new ConcurrentHashMap<>();
    private final Map<UUID, GameBinding> pendingBinding = new ConcurrentHashMap<>();

    private final ChannelEventPublisher channelEventPublisher;

    public boolean isLive(UserAccount user) {
        return liveStatus.getOrDefault(user.getId(), false);
    }

    public void setLive(UserAccount user, boolean live) {
        Boolean previous = liveStatus.put(user.getId(), live);
        // Publish only on transition to avoid spamming subscribers when the
        // same value is reapplied (idempotent setters are common).
        if (previous == null || previous.booleanValue() != live) {
            channelEventPublisher.streamStateChanged(user.getId(), live);
        }
    }

    public void storePending(UserAccount user, GameBinding binding) {
        pendingBinding.put(user.getId(), binding);
    }

    public Optional<GameBinding> getPending(UserAccount user) {
        return Optional.ofNullable(pendingBinding.get(user.getId()));
    }

    public void clearPending(UserAccount user) {
        pendingBinding.remove(user.getId());
    }

    public void clear(UserAccount user) {
        liveStatus.remove(user.getId());
        pendingBinding.remove(user.getId());
    }

    public long countLive() {
        return liveStatus.values().stream().filter(Boolean::booleanValue).count();
    }
}
