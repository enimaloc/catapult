package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
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

    // Only the widget_token column is written: `user` is often a copy loaded earlier (from the
    // request's JWT, an event), and saving it whole would undo whatever changed since.

    @Transactional
    public UUID getOrCreate(UserAccount user) {
        if (user.getWidgetToken() == null) {
            UUID token = UUID.randomUUID();
            if (userAccountRepository.setWidgetTokenIfAbsent(user.getId(), token) == 0) {
                // Another request gave it one first: that one stands
                token = userAccountRepository.findWidgetTokenById(user.getId())
                        .orElseThrow(() -> new IllegalStateException("No account " + user.getId()));
            }
            user.setWidgetToken(token);
        }
        return user.getWidgetToken();
    }

    @Transactional
    public UUID regenerate(UserAccount user) {
        UUID token = UUID.randomUUID();
        userAccountRepository.setWidgetToken(user.getId(), token);
        user.setWidgetToken(token);
        return token;
    }

    public Optional<UserAccount> resolve(UUID token) {
        return userAccountRepository.findByWidgetToken(token);
    }
}
