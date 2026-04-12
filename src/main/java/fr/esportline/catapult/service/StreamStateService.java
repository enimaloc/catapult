package fr.esportline.catapult.service;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.UserAccount;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StreamStateService {

    private final Map<UUID, Boolean> liveStatus = new ConcurrentHashMap<>();
    private final Map<UUID, GameBinding> pendingBinding = new ConcurrentHashMap<>();

    public boolean isLive(UserAccount user) {
        return liveStatus.getOrDefault(user.getId(), false);
    }

    public void setLive(UserAccount user, boolean live) {
        liveStatus.put(user.getId(), live);
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
}
