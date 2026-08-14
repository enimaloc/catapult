package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.TwitchatActionToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.BotToggleService;
import fr.enimaloc.catapult.service.TwitchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TwitchatActionExecutor {

    public enum Result { EXECUTED, ALREADY_USED_OR_EXPIRED, USER_NOT_FOUND, INVALID_PAYLOAD }

    private final TwitchatActionTokenService actionTokenService;
    private final UserAccountRepository userAccountRepository;
    private final TwitchService twitchService;
    private final BindingService bindingService;
    private final BotToggleService botToggleService;

    @Transactional
    public Result execute(UUID actionToken) {
        var tokenOpt = actionTokenService.consume(actionToken);
        if (tokenOpt.isEmpty()) {
            return Result.ALREADY_USED_OR_EXPIRED;
        }
        TwitchatActionToken token = tokenOpt.get();
        var userOpt = userAccountRepository.findById(token.getUserId());
        if (userOpt.isEmpty()) {
            return Result.USER_NOT_FOUND;
        }
        UserAccount user = userOpt.get();
        Map<String, String> payload = actionTokenService.readPayload(token.getPayloadJson());

        switch (token.getActionType()) {
            case REVERT_CATEGORY, REVERT_TO_APP_CATEGORY ->
                    twitchService.setCategory(user, payload.get("gameId"), payload.get("gameName"));
            case BIND_GAME_CATEGORY -> {
                UUID bindingId = parseUuid(payload.get("bindingId"));
                // The token is already burned at this point — a malformed payload must yield a
                // clean result, not a 500.
                if (bindingId == null) {
                    return Result.INVALID_PAYLOAD;
                }
                bindingService.setTwitchGame(user, bindingId,
                        payload.get("newGameId"), payload.get("newGameName"));
            }
            case DISABLE_BOT -> botToggleService.setBotEnabled(user, false);
            case ENABLE_BOT -> botToggleService.setBotEnabled(user, true);
        }
        return Result.EXECUTED;
    }

    private static UUID parseUuid(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
