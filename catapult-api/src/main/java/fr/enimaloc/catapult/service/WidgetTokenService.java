package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

// Resolves the single per-user widget access token shared by every widget-scoped API
// route (twitchat widget today, future ones like game/IGDB/Steam lookups tomorrow) —
// keeping token issuance/rotation independent of any specific widget feature.
@Service
@RequiredArgsConstructor
public class WidgetTokenService {

    private final UserAccountRepository userAccountRepository;

    @Transactional
    public UUID getOrCreate(UserAccount user) {
        if (user.getWidgetToken() == null) {
            user.setWidgetToken(UUID.randomUUID());
            userAccountRepository.save(user);
        }
        return user.getWidgetToken();
    }

    @Transactional
    public UUID regenerate(UserAccount user) {
        user.setWidgetToken(UUID.randomUUID());
        userAccountRepository.save(user);
        return user.getWidgetToken();
    }

    public Optional<UserAccount> resolve(UUID token) {
        return userAccountRepository.findByWidgetToken(token);
    }
}
