package fr.enimaloc.catapult.service.battlenet;

import fr.enimaloc.catapult.bgs.client.BgsClient;
import fr.enimaloc.catapult.bgs.client.FriendEvent;
import fr.enimaloc.catapult.bgs.core.BgsRpcException;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink.Status;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetServiceAccount.AuthState;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.repository.battlenet.BattleNetServiceAccountRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.service.battlenet.BattleNetFriendService.BattleNetEnrollmentException;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BattleNetFriendServiceTest {

    private static final long STREAMER_ID = 100000002L;
    private static final String STREAMER_TAG = "Streamer#2928";

    @Mock private BattleNetServiceAccountRepository accountRepository;
    @Mock private BattleNetFriendLinkRepository linkRepository;
    @Mock private GetterConfigRepository getterConfigRepository;
    @Mock private ChannelEventPublisher publisher;
    @Mock private BattleNetSessionManager sessionManager;
    @Mock private BgsClient client;

    private BattleNetFriendService service;
    private UserAccount user;
    private BattleNetServiceAccount bot1;
    private BattleNetServiceAccount bot2;
    /** The links in "the database", queried by the repository stubs. */
    private final List<BattleNetFriendLink> links = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new BattleNetFriendService(accountRepository, linkRepository, getterConfigRepository, publisher,
                sessionManager, TransactionOperations.withoutTransaction(), Runnable::run);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        bot1 = bot("bot1", 1, "CatapultBot#1111");
        bot2 = bot("bot2", 2, "CatapultBot#2222");

        when(accountRepository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of(bot1, bot2));
        when(accountRepository.findById(bot1.getId())).thenReturn(Optional.of(bot1));
        when(accountRepository.findById(bot2.getId())).thenReturn(Optional.of(bot2));
        when(linkRepository.save(any())).thenAnswer(inv -> {
            BattleNetFriendLink link = inv.getArgument(0);
            if (!links.contains(link)) links.add(link);
            return link;
        });
        when(linkRepository.findWithServiceAccountByUser(any())).thenAnswer(inv -> links.stream()
                .filter(link -> link.getUser() == inv.getArgument(0)).findFirst());
        doAnswer(inv -> links.remove((BattleNetFriendLink) inv.getArgument(0)))
                .when(linkRepository).delete(any());
        when(linkRepository.findByServiceAccountAndBnetAccountId(any(), anyLong())).thenAnswer(inv -> links.stream()
                .filter(link -> link.getServiceAccount() == inv.getArgument(0)
                        && link.getBnetAccountId() == (long) inv.<Long>getArgument(1)).toList());
        when(linkRepository.findByServiceAccountAndBattleTagIgnoreCase(any(), anyString())).thenAnswer(inv ->
                links.stream().filter(link -> link.getServiceAccount() == inv.getArgument(0)
                        && link.getBattleTag().equalsIgnoreCase(inv.getArgument(1))).toList());
        when(linkRepository.findByServiceAccountAndInvitationId(any(), anyLong())).thenAnswer(inv -> links.stream()
                .filter(link -> link.getServiceAccount() == inv.getArgument(0)
                        && Long.valueOf((long) inv.<Long>getArgument(1)).equals(link.getInvitationId())).toList());
        when(getterConfigRepository.findByUserAndProvider(any(), any())).thenReturn(Optional.empty());
        when(getterConfigRepository.findByUserOrderByPriorityAsc(any())).thenReturn(List.of());

        when(sessionManager.withClient(any(), any())).thenAnswer(inv -> {
            Function<BgsClient, CompletableFuture<Void>> action = inv.getArgument(1);
            return action.apply(client);
        });
        when(client.sendInvitation(anyString())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.acceptInvitation(anyLong())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.declineInvitation(anyLong())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.revokeInvitation(anyLong())).thenReturn(CompletableFuture.completedFuture(null));
        when(client.removeFriend(anyLong())).thenReturn(CompletableFuture.completedFuture(null));
    }

    private static BattleNetServiceAccount bot(String label, int fillOrder, String battleTag) {
        BattleNetServiceAccount bot = new BattleNetServiceAccount();
        bot.setId(UUID.randomUUID());
        bot.setLabel(label);
        bot.setFillOrder(fillOrder);
        bot.setBattleTag(battleTag);
        bot.setAuthState(AuthState.READY);
        return bot;
    }

    private BattleNetFriendLink link(BattleNetServiceAccount bot, Status status, Long invitationId) {
        BattleNetFriendLink link = new BattleNetFriendLink();
        link.setUser(user);
        link.setServiceAccount(bot);
        link.setBnetAccountId(STREAMER_ID);
        link.setBattleTag(STREAMER_TAG);
        link.setStatus(status);
        link.setInvitationId(invitationId);
        link.setRequestedAt(Instant.now());
        links.add(link);
        return link;
    }

    private void receive(BattleNetServiceAccount bot, FriendEvent event) {
        service.onFriendEvent(new BattleNetFriendEventReceived(bot.getId(), event));
    }

    // --- enroll ---

    @Test
    void enrollPicksFirstReadyBotByFillOrderAndInvites() {
        bot1.setFriendLimitReached(true);

        BattleNetFriendLink link = service.enroll(user, STREAMER_ID, STREAMER_TAG);

        assertThat(link.getServiceAccount()).isSameAs(bot2);
        assertThat(link.getStatus()).isEqualTo(Status.PENDING);
        assertThat(link.getBnetAccountId()).isEqualTo(STREAMER_ID);
        assertThat(links).containsExactly(link);
        verify(sessionManager).withClient(eq(bot2.getId()), any());
        verify(client).sendInvitation(STREAMER_TAG);
        verify(publisher).battleNetLinkChanged(user.getId(), "PENDING", STREAMER_TAG, "CatapultBot#2222");
    }

    @Test
    void enrollReplacesTheExistingLink() {
        BattleNetFriendLink old = link(bot2, Status.ACCEPTED, null);

        BattleNetFriendLink link = service.enroll(user, STREAMER_ID, STREAMER_TAG);

        assertThat(links).containsExactly(link).doesNotContain(old);
        verify(client).removeFriend(STREAMER_ID);
        verify(publisher).battleNetLinkChanged(user.getId(), "NONE", null, null);
    }

    @Test
    void enrollKeepsTheLinkWhenTheInvitationFails() {
        when(client.sendInvitation(anyString())).thenReturn(CompletableFuture.failedFuture(new BgsRpcException(7)));

        BattleNetFriendLink link = service.enroll(user, STREAMER_ID, STREAMER_TAG);

        assertThat(links).containsExactly(link);
        assertThat(link.getStatus()).isEqualTo(Status.PENDING);
        assertThat(bot1.isFriendLimitReached()).isFalse();
    }

    @Test
    void enrollKeepsTheLinkWithoutAReadySession() {
        doReturn(CompletableFuture.failedFuture(new IllegalStateException("No ready BGS session")))
                .when(sessionManager).withClient(any(), any());

        BattleNetFriendLink link = service.enroll(user, STREAMER_ID, STREAMER_TAG);

        assertThat(links).containsExactly(link);
    }

    @Test
    void enrollMarksTheBotFullWhenItsFriendsListIsFull() {
        when(client.sendInvitation(anyString())).thenReturn(CompletableFuture.failedFuture(
                new BgsRpcException(BattleNetFriendService.STATUS_INVITER_AT_MAX_FRIENDS)));

        service.enroll(user, STREAMER_ID, STREAMER_TAG);

        assertThat(bot1.isFriendLimitReached()).isTrue();
        verify(accountRepository).save(bot1);
    }

    @Test
    void enrollMarksTheBotFullOnAnyFailureAtTheFriendCap() {
        when(client.sendInvitation(anyString())).thenReturn(CompletableFuture.failedFuture(new BgsRpcException(7)));
        when(linkRepository.countByServiceAccountAndStatus(bot1, Status.ACCEPTED))
                .thenReturn((long) BattleNetFriendService.FRIEND_CAP);

        service.enroll(user, STREAMER_ID, STREAMER_TAG);

        assertThat(bot1.isFriendLimitReached()).isTrue();
    }

    @Test
    void enrollWithoutReadyBotThrowsNoAccountAvailable() {
        when(accountRepository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of());
        BattleNetFriendLink existing = link(bot1, Status.ACCEPTED, null);

        assertThatThrownBy(() -> service.enroll(user, STREAMER_ID, STREAMER_TAG))
                .isInstanceOf(BattleNetEnrollmentException.class)
                .extracting(e -> ((BattleNetEnrollmentException) e).getReason())
                .isEqualTo(BattleNetEnrollmentException.Reason.NO_ACCOUNT_AVAILABLE);
        assertThat(links).containsExactly(existing);
    }

    @Test
    void enrollWithEveryBotFullThrowsNoCapacity() {
        bot1.setFriendLimitReached(true);
        bot2.setFriendLimitReached(true);

        assertThatThrownBy(() -> service.enroll(user, STREAMER_ID, STREAMER_TAG))
                .isInstanceOf(BattleNetEnrollmentException.class)
                .extracting(e -> ((BattleNetEnrollmentException) e).getReason())
                .isEqualTo(BattleNetEnrollmentException.Reason.NO_CAPACITY);
        verify(client, never()).sendInvitation(anyString());
    }

    // --- received invitations ---

    @Test
    void expectedInvitationIsAccepted() {
        link(bot1, Status.PENDING, null);

        receive(bot1, new FriendEvent.InvitationReceived(77L, STREAMER_ID, STREAMER_TAG));

        verify(client).acceptInvitation(77L);
        verify(client, never()).declineInvitation(anyLong());
    }

    @Test
    void invitationFromAStrangerIsDeclined() {
        link(bot1, Status.PENDING, null);

        receive(bot1, new FriendEvent.InvitationReceived(78L, 999L, "Stranger#1"));

        verify(client).declineInvitation(78L);
        verify(client, never()).acceptInvitation(anyLong());
    }

    @Test
    void invitationForAnotherBotIsDeclined() {
        link(bot2, Status.PENDING, null);

        receive(bot1, new FriendEvent.InvitationReceived(79L, STREAMER_ID, STREAMER_TAG));

        verify(sessionManager).withClient(eq(bot1.getId()), any());
        verify(client).declineInvitation(79L);
        verify(client, never()).acceptInvitation(anyLong());
    }

    @Test
    void receivedInvitationRemovalIsIgnored() {
        BattleNetFriendLink link = link(bot1, Status.PENDING, null);

        receive(bot1, new FriendEvent.InvitationRemoved(77L, STREAMER_ID, STREAMER_TAG, 3));

        assertThat(link.getStatus()).isEqualTo(Status.PENDING);
        verify(publisher, never()).battleNetLinkChanged(any(), any(), any(), any());
    }

    // --- sent invitations and friends ---

    @Test
    void invitationSentIsMatchedByBattleTagIgnoringCase() {
        BattleNetFriendLink link = link(bot1, Status.PENDING, null);

        receive(bot1, new FriendEvent.InvitationSent(55L, "streamer#2928"));

        assertThat(link.getInvitationId()).isEqualTo(55L);
    }

    @Test
    void invitationSentPrefersThePendingLinkAmongSeveralMatches() {
        BattleNetFriendLink rejected = link(bot1, Status.INVITE_REJECTED, 54L);
        UserAccount other = new UserAccount();
        other.setId(UUID.randomUUID());
        BattleNetFriendLink pending = link(bot1, Status.PENDING, null);
        pending.setUser(other);

        receive(bot1, new FriendEvent.InvitationSent(55L, "STREAMER#2928"));

        assertThat(pending.getInvitationId()).isEqualTo(55L);
        assertThat(rejected.getInvitationId()).isEqualTo(54L);
    }

    @Test
    void friendAddedMarksAcceptedAndEnablesTheGetter() {
        BattleNetFriendLink link = link(bot1, Status.PENDING, 55L);

        receive(bot1, new FriendEvent.FriendAdded(STREAMER_ID, STREAMER_TAG));

        assertThat(link.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(link.getAcceptedAt()).isNotNull();
        ArgumentCaptor<GetterConfig> config = ArgumentCaptor.forClass(GetterConfig.class);
        verify(getterConfigRepository).save(config.capture());
        assertThat(config.getValue().getProvider()).isEqualTo(GetterConfig.Provider.BATTLENET);
        assertThat(config.getValue().isEnabled()).isTrue();
        assertThat(config.getValue().getUser()).isSameAs(user);
        verify(publisher).battleNetLinkChanged(user.getId(), "ACCEPTED", STREAMER_TAG, "CatapultBot#1111");
    }

    @Test
    void friendAddedOnAnotherBotIsIgnored() {
        BattleNetFriendLink link = link(bot2, Status.PENDING, 55L);

        receive(bot1, new FriendEvent.FriendAdded(STREAMER_ID, STREAMER_TAG));

        assertThat(link.getStatus()).isEqualTo(Status.PENDING);
        verify(publisher, never()).battleNetLinkChanged(any(), any(), any(), any());
    }

    @Test
    void sentInvitationRemovedWithReasonZeroThenFriendAddedIsAccepted() {
        BattleNetFriendLink link = link(bot1, Status.PENDING, 55L);

        receive(bot1, new FriendEvent.InvitationSentRemoved(55L, STREAMER_TAG, 0));
        assertThat(link.getStatus()).isEqualTo(Status.PENDING);
        receive(bot1, new FriendEvent.FriendAdded(STREAMER_ID, STREAMER_TAG));

        assertThat(link.getStatus()).isEqualTo(Status.ACCEPTED);
        verify(publisher, never()).battleNetLinkChanged(any(), eq("INVITE_REJECTED"), any(), any());
    }

    @Test
    void sentInvitationRemovedWithNonZeroReasonIsRejected() {
        BattleNetFriendLink link = link(bot1, Status.PENDING, 55L);

        receive(bot1, new FriendEvent.InvitationSentRemoved(55L, STREAMER_TAG, 3));

        assertThat(link.getStatus()).isEqualTo(Status.INVITE_REJECTED);
        verify(publisher).battleNetLinkChanged(user.getId(), "INVITE_REJECTED", STREAMER_TAG, "CatapultBot#1111");
    }

    @Test
    void sentInvitationRemovedOfAnotherInvitationLeavesTheLinkPending() {
        BattleNetFriendLink link = link(bot1, Status.PENDING, 56L);

        receive(bot1, new FriendEvent.InvitationSentRemoved(55L, STREAMER_TAG, 2));

        assertThat(link.getStatus()).isEqualTo(Status.PENDING);
    }

    @Test
    void rejectedThenFriendAddedIsAccepted() {
        BattleNetFriendLink link = link(bot1, Status.INVITE_REJECTED, 55L);

        receive(bot1, new FriendEvent.FriendAdded(STREAMER_ID, STREAMER_TAG));

        assertThat(link.getStatus()).isEqualTo(Status.ACCEPTED);
    }

    @Test
    void goneWhileOfflineThenFriendAddedIsAccepted() {
        BattleNetFriendLink link = link(bot1, Status.PENDING, 55L);

        receive(bot1, new FriendEvent.InvitationSentRemoved(55L, STREAMER_TAG,
                FriendEvent.REASON_GONE_WHILE_OFFLINE));
        receive(bot1, new FriendEvent.FriendAdded(STREAMER_ID, STREAMER_TAG));

        assertThat(link.getStatus()).isEqualTo(Status.ACCEPTED);
    }

    @Test
    void friendAddedThenGoneWhileOfflineStaysAccepted() {
        BattleNetFriendLink link = link(bot1, Status.PENDING, 55L);

        receive(bot1, new FriendEvent.FriendAdded(STREAMER_ID, STREAMER_TAG));
        receive(bot1, new FriendEvent.InvitationSentRemoved(55L, STREAMER_TAG,
                FriendEvent.REASON_GONE_WHILE_OFFLINE));

        assertThat(link.getStatus()).isEqualTo(Status.ACCEPTED);
        verify(publisher, never()).battleNetLinkChanged(any(), eq("INVITE_REJECTED"), any(), any());
    }

    @Test
    void friendRemovedMarksRemoved() {
        BattleNetFriendLink link = link(bot1, Status.ACCEPTED, 55L);

        receive(bot1, new FriendEvent.FriendRemoved(STREAMER_ID, STREAMER_TAG));

        assertThat(link.getStatus()).isEqualTo(Status.REMOVED);
        verify(publisher).battleNetLinkChanged(user.getId(), "REMOVED", STREAMER_TAG, "CatapultBot#1111");
    }

    @Test
    void eventOfAnUnknownBotIsIgnored() {
        BattleNetFriendLink link = link(bot1, Status.PENDING, 55L);

        service.onFriendEvent(new BattleNetFriendEventReceived(UUID.randomUUID(),
                new FriendEvent.FriendAdded(STREAMER_ID, STREAMER_TAG)));

        assertThat(link.getStatus()).isEqualTo(Status.PENDING);
        verify(publisher, never()).battleNetLinkChanged(any(), any(), any(), any());
    }

    @Test
    void eventOfAnUnknownFriendIsIgnored() {
        receive(bot1, new FriendEvent.FriendAdded(999L, "Stranger#1"));
        receive(bot1, new FriendEvent.InvitationSent(12L, "Stranger#1"));

        verify(linkRepository, never()).save(any());
        verify(publisher, never()).battleNetLinkChanged(any(), any(), any(), any());
    }

    // --- unenroll ---

    @Test
    void unenrollRevokesPendingAndRemovesAccepted() {
        link(bot1, Status.PENDING, 55L);
        service.unenroll(user);
        verify(client).revokeInvitation(55L);
        assertThat(links).isEmpty();

        link(bot1, Status.ACCEPTED, 55L);
        service.unenroll(user);
        verify(client).removeFriend(STREAMER_ID);
        assertThat(links).isEmpty();
    }

    @Test
    void unenrollDeletesTheLinkWhenTheCallFails() {
        link(bot1, Status.ACCEPTED, null);
        when(client.removeFriend(anyLong())).thenReturn(CompletableFuture.failedFuture(new BgsRpcException(7)));

        service.unenroll(user);

        assertThat(links).isEmpty();
        verify(publisher).battleNetLinkChanged(user.getId(), "NONE", null, null);
    }

    @Test
    void unenrollWithoutLinkDoesNothing() {
        service.unenroll(user);

        verify(sessionManager, never()).withClient(any(), any());
        verify(publisher, never()).battleNetLinkChanged(any(), any(), any(), any());
    }

    @Test
    void everyStatusChangePublishesBattleNetLinkChanged() {
        service.enroll(user, STREAMER_ID, STREAMER_TAG);
        receive(bot1, new FriendEvent.InvitationSent(55L, STREAMER_TAG));
        receive(bot1, new FriendEvent.InvitationSentRemoved(55L, STREAMER_TAG, 3));
        receive(bot1, new FriendEvent.FriendAdded(STREAMER_ID, STREAMER_TAG));
        receive(bot1, new FriendEvent.FriendRemoved(STREAMER_ID, STREAMER_TAG));
        service.unenroll(user);

        var inOrder = inOrder(publisher);
        inOrder.verify(publisher).battleNetLinkChanged(user.getId(), "PENDING", STREAMER_TAG, "CatapultBot#1111");
        inOrder.verify(publisher).battleNetLinkChanged(user.getId(), "INVITE_REJECTED", STREAMER_TAG,
                "CatapultBot#1111");
        inOrder.verify(publisher).battleNetLinkChanged(user.getId(), "ACCEPTED", STREAMER_TAG, "CatapultBot#1111");
        inOrder.verify(publisher).battleNetLinkChanged(user.getId(), "REMOVED", STREAMER_TAG, "CatapultBot#1111");
        inOrder.verify(publisher).battleNetLinkChanged(eq(user.getId()), eq("NONE"), isNull(), isNull());
        inOrder.verifyNoMoreInteractions();
    }
}
