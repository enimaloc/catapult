package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.chat.ChatCommandEvent.SenderRole;
import fr.enimaloc.catapult.config.I18nConfig;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The replies as chat receives them, formatted by the real message source. */
class SystemMentionRepliesTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final EmergencyStopService emergency = mock(EmergencyStopService.class);
    private final TwitchChatService chat = mock(TwitchChatService.class);
    private final EmergencyContextCollector collector = mock(EmergencyContextCollector.class);
    private final SystemMentionCommands commands = new SystemMentionCommands(users, mock(BotToggleService.class),
            emergency, collector, mock(GameStateService.class), mock(ActivityLogService.class), chat,
            new I18nConfig().messageSource(), new EmergencyProperties());
    private final UserAccount channel = new UserAccount();

    private List<String> repliesTo(SenderRole role, String senderId, SystemMention mention) {
        channel.setId(UUID.randomUUID());
        when(users.findById(channel.getId())).thenReturn(Optional.of(channel));
        when(collector.collect(any())).thenReturn(new ReportContext("{}", null, null));
        ReflectionTestUtils.setField(commands, "ownerId", "owner-1");
        commands.handle(channel, new MentionSender(senderId, "login", role), mention);
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        verify(chat, atLeastOnce()).sendMessage(eq(channel), sent.capture());
        return sent.getAllValues();
    }

    @Test
    void noArgReply_hasASingleApostrophe() {
        when(emergency.activeStops()).thenReturn(List.of());

        assertThat(repliesTo(SenderRole.VIEWERS, "owner-1", new SystemMention("emergency", List.of("status"))))
                .containsExactly("Aucun arrêt d'urgence actif.");
    }

    @Test
    void formattedReplies_haveSingleApostrophesAndDisplayNames() {
        UserAccount system = new UserAccount();
        system.setTwitchUsername("catapult_bot");
        when(users.findBySystemAccountTrue()).thenReturn(Optional.of(system));
        when(emergency.isProviderUsed(any(), eq(GetterConfig.Provider.BATTLENET))).thenReturn(true);
        when(emergency.report(any(), eq(GetterConfig.Provider.BATTLENET), any(), any(), any(), any(), any()))
                .thenReturn(new ReportOutcome(ReportOutcome.Kind.CHANNEL_STOP, 1, 3, Instant.now()));

        assertThat(repliesTo(SenderRole.MODERATOR, "m-1", new SystemMention("emergency", List.of("battlenet"))))
                .containsExactly("Battle.net coupé sur cette chaîne pour 24 h (signalement 1/3). "
                        + "Pour reprendre : @catapult_bot on");
    }

    @Test
    void notUsedReply_hasASingleApostrophe() {
        when(emergency.isProviderUsed(any(), eq(GetterConfig.Provider.EPIC))).thenReturn(false);

        assertThat(repliesTo(SenderRole.BROADCASTER, "b-1", new SystemMention("emergency", List.of("epic"))))
                .containsExactly("Cette chaîne n'utilise pas Epic Games : rien à arrêter.");
    }
}
