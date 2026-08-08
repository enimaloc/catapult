package fr.enimaloc.catapult.service.notification;

import fr.enimaloc.catapult.domain.TwitchatActionToken;
import fr.enimaloc.catapult.domain.TwitchatActionType;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.BindingService;
import fr.enimaloc.catapult.service.BotToggleService;
import fr.enimaloc.catapult.service.TwitchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TwitchatActionExecutorTest {

    @Mock private TwitchatActionTokenService actionTokenService;
    @Mock private UserAccountRepository userAccountRepository;
    @Mock private TwitchService twitchService;
    @Mock private BindingService bindingService;
    @Mock private BotToggleService botToggleService;
    @InjectMocks private TwitchatActionExecutor executor;

    private UserAccount user;
    private UUID actionToken;

    @BeforeEach
    void setup() {
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        actionToken = UUID.randomUUID();
        lenient().when(userAccountRepository.findById(user.getId())).thenReturn(Optional.of(user));
    }

    private TwitchatActionToken tokenOf(TwitchatActionType type, Map<String, String> payload, String json) {
        TwitchatActionToken t = new TwitchatActionToken();
        t.setToken(actionToken);
        t.setUserId(user.getId());
        t.setActionType(type);
        t.setPayloadJson(json);
        lenient().when(actionTokenService.readPayload(json)).thenReturn(payload);
        return t;
    }

    @Test
    void execute_alreadyConsumedOrExpired_returnsAlreadyUsed() {
        when(actionTokenService.consume(actionToken)).thenReturn(Optional.empty());

        var result = executor.execute(actionToken);

        assertThat(result).isEqualTo(TwitchatActionExecutor.Result.ALREADY_USED_OR_EXPIRED);
        verifyNoInteractions(twitchService, bindingService, botToggleService);
    }

    @Test
    void execute_revertCategory_callsSetCategory() {
        TwitchatActionToken token = tokenOf(TwitchatActionType.REVERT_CATEGORY,
                Map.of("gameId", "111", "gameName", "Old Game"), "{}");
        when(actionTokenService.consume(actionToken)).thenReturn(Optional.of(token));

        var result = executor.execute(actionToken);

        assertThat(result).isEqualTo(TwitchatActionExecutor.Result.EXECUTED);
        verify(twitchService).setCategory(user, "111", "Old Game");
    }

    @Test
    void execute_bindGameCategory_callsSetTwitchGame() {
        UUID bindingId = UUID.randomUUID();
        TwitchatActionToken token = tokenOf(TwitchatActionType.BIND_GAME_CATEGORY,
                Map.of("bindingId", bindingId.toString(), "newGameId", "222", "newGameName", "New Game"), "{}");
        when(actionTokenService.consume(actionToken)).thenReturn(Optional.of(token));

        executor.execute(actionToken);

        verify(bindingService).setTwitchGame(user, bindingId, "222", "New Game");
    }

    @Test
    void execute_disableBot_callsSetBotEnabledFalse() {
        TwitchatActionToken token = tokenOf(TwitchatActionType.DISABLE_BOT, Map.of(), "{}");
        when(actionTokenService.consume(actionToken)).thenReturn(Optional.of(token));

        executor.execute(actionToken);

        verify(botToggleService).setBotEnabled(user, false);
    }

    @Test
    void execute_enableBot_callsSetBotEnabledTrue() {
        TwitchatActionToken token = tokenOf(TwitchatActionType.ENABLE_BOT, Map.of(), "{}");
        when(actionTokenService.consume(actionToken)).thenReturn(Optional.of(token));

        executor.execute(actionToken);

        verify(botToggleService).setBotEnabled(user, true);
    }

    @Test
    void execute_userNotFound_returnsUserNotFound() {
        TwitchatActionToken token = tokenOf(TwitchatActionType.DISABLE_BOT, Map.of(), "{}");
        when(actionTokenService.consume(actionToken)).thenReturn(Optional.of(token));
        when(userAccountRepository.findById(user.getId())).thenReturn(Optional.empty());

        var result = executor.execute(actionToken);

        assertThat(result).isEqualTo(TwitchatActionExecutor.Result.USER_NOT_FOUND);
        verifyNoInteractions(botToggleService);
    }
}
