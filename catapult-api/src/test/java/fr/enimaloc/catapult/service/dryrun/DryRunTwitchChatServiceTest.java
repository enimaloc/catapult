package fr.enimaloc.catapult.service.dryrun;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.debug.DebugExchange.Direction;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.account.UserSettingsRepository;
import fr.enimaloc.catapult.repository.dryrun.MemberDryRunRepository;
import fr.enimaloc.catapult.service.ActivityLogService;
import fr.enimaloc.catapult.service.debug.DebugExchangeRecorder;
import fr.enimaloc.catapult.service.debug.DebugMemberRegistry;
import fr.enimaloc.catapult.service.title.StreamTitleService;
import fr.enimaloc.catapult.service.twitch.TwitchChatService;
import fr.enimaloc.catapult.service.twitch.TwitchStreamInfo;
import fr.enimaloc.catapult.service.twitch.TwitchUserProfile;
import fr.enimaloc.catapult.site.SiteRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static fr.enimaloc.catapult.service.dryrun.DryRunTwitchServiceTest.provider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DryRunTwitchChatServiceTest {

    final DryRunRegistry registry = new DryRunRegistry(mock(), event -> { });
    final UserAccountRepository users = mock(UserAccountRepository.class);
    final SiteRole siteRole = mock(SiteRole.class);
    final ActivityLogService activityLog = mock(ActivityLogService.class);
    final DebugMemberRegistry debugMembers = mock(DebugMemberRegistry.class);
    final DebugExchangeRecorder recorder = mock(DebugExchangeRecorder.class);
    final DryRunGate gate = new DryRunGate(registry, users, mock(UserSettingsRepository.class), siteRole, activityLog,
            debugMembers, recorder, mock(StreamTitleService.class), provider(null), provider(null));
    final TwitchChatService delegate = mock(TwitchChatService.class);
    final DryRunTwitchChatService chat = new DryRunTwitchChatService(delegate, () -> gate);

    final UUID member = UUID.randomUUID();
    final UserAccount account = new UserAccount();

    @BeforeEach
    void setUp() {
        account.setId(member);
        when(users.findById(member)).thenReturn(Optional.of(account));
        when(siteRole.isActive()).thenReturn(true);
        when(debugMembers.modeOf(any())).thenReturn(Optional.empty());
        registry.load();
        registry.activate(member);
    }

    @Test
    void aChatWriteBeforeTheFirstLoadIsHeld() {
        MemberDryRunRepository unreadable = mock(MemberDryRunRepository.class);
        when(unreadable.findAll()).thenThrow(new IllegalStateException("database down"));
        DryRunRegistry unloaded = new DryRunRegistry(unreadable, event -> { });
        unloaded.init();
        DryRunGate unloadedGate = new DryRunGate(unloaded, users, mock(UserSettingsRepository.class), siteRole,
                activityLog, debugMembers, recorder, mock(StreamTitleService.class), provider(null), provider(null));
        DryRunTwitchChatService held = new DryRunTwitchChatService(delegate, () -> unloadedGate);
        UserAccount other = new UserAccount();
        other.setId(UUID.randomUUID());

        held.sendMessage(other, "Now playing Factorio");
        held.timeout(other, "viewer", 60, "spam");
        held.ban(other, "viewer", "spam");
        held.unban(other, "viewer");
        held.shoutout(other, "friend");

        verifyNoInteractions(delegate, activityLog);
    }

    @Test
    void blocksSendMessageAndLogsIt() {
        chat.sendMessage(account, "Now playing Factorio");

        verifyNoInteractions(delegate);
        verify(activityLog).addEntry(member, "INFO", "[dry run] would have sent: Now playing Factorio");
    }

    @Test
    void aBlankMessageIsNeitherSentNorLogged() {
        chat.sendMessage(account, " ");

        verifyNoInteractions(delegate, activityLog);
    }

    @Test
    void blocksTimeoutAndLogsIt() {
        chat.timeout(account, "spammer", 600, "spam");

        verifyNoInteractions(delegate);
        verify(activityLog).addEntry(member, "INFO", "[dry run] would have timed out spammer for 600s");
    }

    @Test
    void aTimeoutOfNoDurationIsNeitherSentNorLogged() {
        chat.timeout(account, "spammer", 0, "spam");

        verifyNoInteractions(delegate, activityLog);
    }

    @Test
    void blocksBanAndLogsIt() {
        chat.ban(account, "troll", "rude");

        verifyNoInteractions(delegate);
        verify(activityLog).addEntry(member, "INFO", "[dry run] would have banned troll");
    }

    @Test
    void blocksUnbanAndLogsIt() {
        chat.unban(account, "troll");

        verifyNoInteractions(delegate);
        verify(activityLog).addEntry(member, "INFO", "[dry run] would have unbanned troll");
    }

    @Test
    void blocksShoutoutAndLogsIt() {
        chat.shoutout(account, "friend");

        verifyNoInteractions(delegate);
        verify(activityLog).addEntry(member, "INFO", "[dry run] would have shouted out friend");
    }

    @Test
    void aBlockedMessageIsRecordedInTheDebugJournalOfAMemberInDebug() {
        when(debugMembers.modeOf(member)).thenReturn(Optional.of(new DebugMemberRegistry.DebugMode(member, false)));

        chat.sendMessage(account, "hello");

        verify(recorder).record(eq(member), eq(Direction.OUT), any(), eq("dry-run.sendMessage"), eq(false),
                eq("twitch"));
    }

    @Test
    void aFailingLogNeverReachesTheCaller() {
        doThrow(new IllegalStateException("log down")).when(activityLog).addEntry(any(), anyString(), anyString());

        chat.ban(account, "troll", "rude");

        verifyNoInteractions(delegate);
    }

    @Test
    void readsPassThrough() {
        TwitchStreamInfo stream = new TwitchStreamInfo("1", "title", "Factorio", 3, Instant.EPOCH);
        TwitchUserProfile profile = new TwitchUserProfile("Friend", Instant.EPOCH);
        when(delegate.getStreamInfo(account)).thenReturn(Optional.of(stream));
        when(delegate.archiveVideoId(account, "1")).thenReturn(Optional.of("v1"));
        when(delegate.getUserProfile(account, "friend")).thenReturn(Optional.of(profile));
        when(delegate.getFollowedAt(account, "friend")).thenReturn(Optional.of(Instant.EPOCH));
        when(delegate.getFollowedAtById(account, "42")).thenReturn(Optional.of(Instant.EPOCH));
        when(delegate.isConnected(account)).thenReturn(true);

        assertThat(chat.getStreamInfo(account)).contains(stream);
        assertThat(chat.archiveVideoId(account, "1")).contains("v1");
        assertThat(chat.getUserProfile(account, "friend")).contains(profile);
        assertThat(chat.getFollowedAt(account, "friend")).contains(Instant.EPOCH);
        assertThat(chat.getFollowedAtById(account, "42")).contains(Instant.EPOCH);
        assertThat(chat.isConnected(account)).isTrue();
        chat.connect(account);
        chat.disconnect(account);
        chat.disconnectAll("standby");

        verify(delegate).connect(account);
        verify(delegate).disconnect(account);
        verify(delegate).disconnectAll("standby");
        verifyNoInteractions(activityLog);
    }

    @Test
    void aMemberNotInDryRunIsUntouched() {
        registry.deactivate(member);

        chat.sendMessage(account, "hello");
        chat.timeout(account, "spammer", 600, "spam");
        chat.ban(account, "troll", "rude");
        chat.unban(account, "troll");
        chat.shoutout(account, "friend");

        verify(delegate).sendMessage(account, "hello");
        verify(delegate).timeout(account, "spammer", 600, "spam");
        verify(delegate).ban(account, "troll", "rude");
        verify(delegate).unban(account, "troll");
        verify(delegate).shoutout(account, "friend");
        verifyNoInteractions(activityLog, recorder);
    }

    @Test
    void aStandbySiteStillRefusesBeforeTheGate() {
        when(siteRole.isActive()).thenReturn(false);

        chat.sendMessage(account, "hello");

        verify(delegate).sendMessage(account, "hello");
        verifyNoInteractions(activityLog);
    }
}
