package fr.enimaloc.catapult.service.emergency;

import fr.enimaloc.catapult.chat.ChatCommandEvent.SenderRole;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.emergency.ProviderEmergencyStop;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.account.BotToggleService;
import fr.enimaloc.catapult.service.binding.GameStateService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SystemMentionCommandsTest {

    private final UserAccountRepository users = mock(UserAccountRepository.class);
    private final BotToggleService botToggle = mock(BotToggleService.class);
    private final EmergencyStopService emergency = mock(EmergencyStopService.class);
    private final EmergencyContextCollector collector = mock(EmergencyContextCollector.class);
    private final GameStateService gameState = mock(GameStateService.class);
    private final ActivityLogService activityLog = mock(ActivityLogService.class);
    private final TwitchChatService chat = mock(TwitchChatService.class);
    private final MessageSource messages = mock(MessageSource.class);
    private SystemMentionCommands commands;
    private UserAccount channel;
    private UserAccount fresh;

    @BeforeEach
    void setUp() {
        commands = new SystemMentionCommands(users, botToggle, emergency, collector, gameState, activityLog,
                chat, messages, new EmergencyProperties());
        ReflectionTestUtils.setField(commands, "ownerId", "owner-1");
        UUID id = UUID.randomUUID();
        channel = new UserAccount();
        channel.setId(id);
        channel.setTwitchUsername("streamer");
        fresh = new UserAccount();
        fresh.setId(id);
        fresh.setTwitchUsername("streamer");
        when(users.findById(id)).thenReturn(Optional.of(fresh));
        // A reply is its key followed by its arguments, so a test can tell which one went out
        when(messages.getMessage(anyString(), any(Object[].class), any(java.util.Locale.class)))
                .thenAnswer(call -> call.getArgument(0, String.class)
                        + java.util.Arrays.toString(call.getArgument(1, Object[].class)));
        when(collector.collect(any())).thenReturn(new ReportContext("{}", null, null));
        when(emergency.isProviderUsed(any(), any())).thenReturn(true);
    }

    private static MentionSender sender(String id, SenderRole role) {
        return new MentionSender(id, "login-" + id, role);
    }

    private static SystemMention mention(String command, String... args) {
        return new SystemMention(command, List.of(args));
    }

    private void repliedWith(String keyPrefix) {
        verify(chat).sendMessage(eq(fresh), org.mockito.ArgumentMatchers.startsWith(keyPrefix));
    }

    @Test
    void off_byModerator_confirmsThenDisables() {
        commands.handle(channel, sender("m-1", SenderRole.MODERATOR), mention("off"));

        InOrder order = inOrder(chat, botToggle);
        order.verify(chat).sendMessage(eq(fresh), org.mockito.ArgumentMatchers.startsWith("bot.mention.off"));
        order.verify(botToggle).setBotEnabled(fresh, false);
        verify(activityLog).addEntry(eq(fresh.getId()), anyString(), anyString());
    }

    @Test
    void off_byViewer_isIgnored() {
        commands.handle(channel, sender("v-1", SenderRole.VIEWERS), mention("off"));

        verifyNoInteractions(botToggle, chat, emergency);
    }

    @Test
    void on_liftsChannelStop() {
        fresh.setBotEnabled(false);

        commands.handle(channel, sender("b-1", SenderRole.BROADCASTER), mention("on"));

        InOrder order = inOrder(botToggle, emergency, chat);
        order.verify(botToggle).setBotEnabled(fresh, true);
        order.verify(emergency).liftChannel(fresh, "login-b-1");
        order.verify(chat).sendMessage(eq(fresh), org.mockito.ArgumentMatchers.startsWith("bot.mention.on"));
        verify(activityLog).addEntry(eq(fresh.getId()), anyString(), anyString());
    }

    @Test
    void disabledBot_ignoresEverythingButOn() {
        fresh.setBotEnabled(false);

        commands.handle(channel, sender("m-1", SenderRole.MODERATOR), mention("off"));
        commands.handle(channel, sender("m-1", SenderRole.MODERATOR), mention("emergency", "steam"));
        commands.handle(channel, sender("owner-1", SenderRole.VIEWERS), mention("emergency", "status"));

        verifyNoInteractions(chat, emergency, collector);
        verify(botToggle, never()).setBotEnabled(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void disabledBot_readFromDatabase_notFromStaleUser() {
        // The connection captured the user while enabled; the database says it has since been disabled
        channel.setBotEnabled(true);
        fresh.setBotEnabled(false);

        commands.handle(channel, sender("m-1", SenderRole.MODERATOR), mention("emergency", "steam"));

        verifyNoInteractions(chat, emergency, collector);
    }

    @Test
    void unknownCommand_isIgnoredSilently() {
        commands.handle(channel, sender("m-1", SenderRole.MODERATOR), mention("dance"));

        verifyNoInteractions(chat, emergency, botToggle);
    }

    @Test
    void owner_asViewer_stopsGloballyWithoutThreshold() {
        commands.handle(channel, sender("owner-1", SenderRole.VIEWERS), mention("emergency", "steam"));

        verify(emergency).ownerStop("steam", "login-owner-1");
        verify(emergency, never()).report(any(), any(), any(), any(), any(), any(), any());
        repliedWith("bot.mention.owner.stopped");
    }

    @Test
    void owner_resumeWithoutTarget_asksForTheProvider() {
        commands.handle(channel, sender("owner-1", SenderRole.VIEWERS), mention("emergency", "resume"));

        repliedWith("bot.mention.provider.required");
        verify(emergency, never()).ownerResume(any(), any());
    }

    @Test
    void owner_resume_liftsTheProvider() {
        when(emergency.ownerResume("all", "login-owner-1")).thenReturn(2);

        commands.handle(channel, sender("owner-1", SenderRole.VIEWERS), mention("emergency", "resume", "all"));

        verify(emergency).ownerResume("all", "login-owner-1");
        repliedWith("bot.mention.owner.resumed");
    }

    @Test
    void emergency_noArgNoLastGame_asksForProvider() {
        when(gameState.getLastKnownGame(fresh)).thenReturn(Optional.empty());

        commands.handle(channel, sender("m-1", SenderRole.MODERATOR), mention("emergency"));

        repliedWith("bot.mention.provider.required");
        verify(emergency, never()).report(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void emergency_manualLastGame_asksForProvider() {
        when(gameState.getLastKnownGame(fresh)).thenReturn(
                Optional.of(new DetectedGame("1", GameBinding.SourceType.MANUAL, "Game")));

        commands.handle(channel, sender("m-1", SenderRole.MODERATOR), mention("emergency"));

        repliedWith("bot.mention.provider.required");
    }

    @Test
    void emergency_deducesProviderFromTheLastGame() {
        when(gameState.getLastKnownGame(fresh)).thenReturn(
                Optional.of(new DetectedGame("1", GameBinding.SourceType.XBOX, "Game")));
        when(emergency.report(any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new ReportOutcome(ReportOutcome.Kind.CHANNEL_STOP, 1, 3, Instant.now()));

        commands.handle(channel, sender("m-1", SenderRole.MODERATOR), mention("emergency"));

        verify(emergency).report(eq(fresh), eq(GetterConfig.Provider.XBOX), eq("m-1"), eq("login-m-1"),
                eq("{}"), any(), any());
        repliedWith("bot.mention.emergency.channelStop");
    }

    @Test
    void emergency_channelStop_givesTheDurationAndHowToResume() {
        UserAccount system = new UserAccount();
        system.setTwitchUsername("catapult_bot");
        when(users.findBySystemAccountTrue()).thenReturn(Optional.of(system));
        when(emergency.report(any(), eq(GetterConfig.Provider.XBOX), any(), any(), any(), any(), any()))
                .thenReturn(new ReportOutcome(ReportOutcome.Kind.CHANNEL_STOP, 1, 3, Instant.now()));

        commands.handle(channel, sender("m-1", SenderRole.MODERATOR), mention("emergency", "xbox"));

        verify(chat).sendMessage(fresh, "bot.mention.emergency.channelStop[XBOX, 1, 3, 24, @catapult_bot]"
                );
    }

    @Test
    void emergency_reportFails_repliesWithAGenericError() {
        when(emergency.report(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));

        commands.handle(channel, sender("m-1", SenderRole.MODERATOR), mention("emergency", "steam"));

        repliedWith("bot.mention.error");
    }

    @Test
    void emergency_providerNotUsed_explainsWithoutGatheringTheContext() {
        when(emergency.isProviderUsed(fresh, GetterConfig.Provider.EPIC)).thenReturn(false);

        commands.handle(channel, sender("b-1", SenderRole.BROADCASTER), mention("emergency", "EPIC"));

        repliedWith("bot.mention.emergency.notUsed");
        verifyNoInteractions(collector);
        verify(emergency, never()).report(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void emergency_unknownProvider_getsAShortAnswer() {
        commands.handle(channel, sender("m-1", SenderRole.MODERATOR), mention("emergency", "nope"));

        repliedWith("bot.mention.provider.unknown");
        verify(emergency, never()).report(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void emergency_allIsNotAProviderForAModerator() {
        commands.handle(channel, sender("m-1", SenderRole.MODERATOR), mention("emergency", "all"));

        repliedWith("bot.mention.provider.unknown");
        verify(emergency, never()).ownerStop(any(), any());
    }

    @Test
    void emergency_status_listsActiveStops() {
        ProviderEmergencyStop stop = new ProviderEmergencyStop();
        stop.setProvider("STEAM");
        stop.setScope(ProviderEmergencyStop.Scope.GLOBAL);
        stop.setExpiresAt(Instant.parse("2026-11-20T10:00:00Z"));
        when(emergency.activeStops()).thenReturn(List.of(stop));

        commands.handle(channel, sender("owner-1", SenderRole.VIEWERS), mention("emergency", "status"));

        verify(chat).sendMessage(eq(fresh), org.mockito.ArgumentMatchers.contains("STEAM"));
        verify(emergency, never()).ownerStop(any(), any());
    }

    @Test
    void emergency_statusWithNothingActive_saysSo() {
        when(emergency.activeStops()).thenReturn(List.of());

        commands.handle(channel, sender("owner-1", SenderRole.VIEWERS), mention("emergency", "status"));

        repliedWith("bot.mention.status.none");
    }
}
