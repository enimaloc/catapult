package fr.enimaloc.catapult.service.ankama;

import fr.enimaloc.catapult.common.dto.connect.AnkamaLinkStateResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink;
import fr.enimaloc.catapult.domain.ankama.AnkamaFriendLink.Status;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount;
import fr.enimaloc.catapult.domain.ankama.AnkamaServiceAccount.AuthState;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.repository.ankama.AnkamaFriendLinkRepository;
import fr.enimaloc.catapult.repository.ankama.AnkamaServiceAccountRepository;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.service.ankama.AnkamaFriendService.AnkamaEnrollmentException;
import fr.enimaloc.catapult.service.ankama.chat.ChatFrames;
import fr.enimaloc.catapult.service.ankama.chat.ChatMessages;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnkamaFriendServiceTest {

    private static final long BOT_ID = 5000L;
    private static final long STREAMER_ID = 7000L;
    private static final ChatMessages.User BOT_USER = new ChatMessages.User(BOT_ID, "Bot", "1111");
    private static final ChatMessages.User STREAMER = new ChatMessages.User(STREAMER_ID, "Enimaloc", "6094");

    @Mock private AnkamaServiceAccountRepository accountRepository;
    @Mock private AnkamaFriendLinkRepository linkRepository;
    @Mock private GetterConfigRepository getterConfigRepository;
    @Mock private ChannelEventPublisher publisher;
    @Mock private AnkamaSessionManager sessionManager;

    private AnkamaFriendService service;
    private UserAccount user;
    private AnkamaServiceAccount bot1;
    private AnkamaServiceAccount bot2;
    private final List<AnkamaFriendLink> links = new ArrayList<>();
    private final List<GetterConfig> getters = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new AnkamaFriendService(accountRepository, linkRepository, getterConfigRepository, publisher,
                sessionManager, TransactionOperations.withoutTransaction(), 2);
        user = new UserAccount();
        user.setId(UUID.randomUUID());
        bot1 = bot(BOT_ID, 1, "Bot", "1111");
        bot2 = bot(BOT_ID + 1, 2, "Bot", "2222");

        when(accountRepository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of(bot1, bot2));
        when(accountRepository.findById(bot1.getId())).thenReturn(Optional.of(bot1));
        when(accountRepository.findById(bot2.getId())).thenReturn(Optional.of(bot2));
        when(linkRepository.save(any())).thenAnswer(inv -> {
            AnkamaFriendLink link = inv.getArgument(0);
            if (link.getId() == null) link.setId(UUID.randomUUID());
            if (!links.contains(link)) links.add(link);
            return link;
        });
        when(linkRepository.findWithServiceAccountByUser(any())).thenAnswer(inv -> links.stream()
                .filter(link -> link.getUser() == inv.getArgument(0)).findFirst());
        doAnswer(inv -> links.remove((AnkamaFriendLink) inv.getArgument(0))).when(linkRepository).delete(any());
        when(linkRepository.findByServiceAccountId(any())).thenAnswer(inv -> links.stream()
                .filter(link -> link.getServiceAccount().getId().equals(inv.getArgument(0))).toList());
        when(linkRepository.countByServiceAccountIdAndStatusIn(any(), any())).thenAnswer(inv -> {
            Collection<Status> statuses = inv.getArgument(1);
            return links.stream().filter(link -> link.getServiceAccount().getId().equals(inv.getArgument(0))
                    && statuses.contains(link.getStatus())).count();
        });
        when(getterConfigRepository.findByUserAndProvider(any(), any())).thenAnswer(inv -> getters.stream()
                .filter(config -> config.getProvider() == inv.getArgument(1)).findFirst());
        when(getterConfigRepository.findByUserOrderByPriorityAsc(any())).thenReturn(List.of());
        when(getterConfigRepository.save(any())).thenAnswer(inv -> {
            GetterConfig config = inv.getArgument(0);
            if (!getters.contains(config)) getters.add(config);
            return config;
        });
        when(sessionManager.request(any(), any())).thenReturn(new CompletableFuture<>());
        when(linkRepository.existsByUserIdNotAndStatusNotAndRequestedNameIgnoreCaseAndRequestedTag(any(), any(),
                any(), any())).thenAnswer(inv -> links.stream().anyMatch(link ->
                !link.getUser().getId().equals(inv.getArgument(0)) && link.getStatus() != inv.getArgument(1)
                        && link.getRequestedName().equalsIgnoreCase(inv.getArgument(2))
                        && link.getRequestedTag().equals(inv.getArgument(3))));
        when(linkRepository.findByUserId(any())).thenAnswer(inv -> links.stream()
                .filter(link -> link.getUser().getId().equals(inv.getArgument(0))).findFirst());
    }

    private AnkamaFriendLink otherUsersLink(Status status, String name) {
        UserAccount other = new UserAccount();
        other.setId(UUID.randomUUID());
        AnkamaFriendLink link = new AnkamaFriendLink();
        link.setId(UUID.randomUUID());
        link.setUser(other);
        link.setServiceAccount(bot1);
        link.setRequestedName(name);
        link.setRequestedTag("6094");
        link.setStatus(status);
        links.add(link);
        return link;
    }

    @Test
    void enroll_refusesAnIdentityAnotherUserHasLinked_whateverTheCase() {
        otherUsersLink(Status.PENDING, "ENIMALOC");

        assertThatThrownBy(() -> service.enroll(user, "enimaloc#6094"))
                .isInstanceOfSatisfying(AnkamaEnrollmentException.class, e ->
                        assertThat(e.getReason()).isEqualTo(AnkamaEnrollmentException.Reason.ALREADY_LINKED));
        assertThat(links).hasSize(1);
    }

    @Test
    void enroll_allowsAnIdentityWhoseOtherLinkIsRemoved() {
        otherUsersLink(Status.REMOVED, "Enimaloc");

        assertThat(service.enroll(user, "Enimaloc#6094").getStatus()).isEqualTo(Status.PENDING);
    }

    @Test
    void enroll_sameUserAgainWithTheSameTag_isAllowed() {
        link(bot1, Status.PENDING, null);

        AnkamaFriendLink link = service.enroll(user, "Enimaloc#6094");

        assertThat(links).containsExactly(link);
    }

    @Test
    void enroll_refusesAKnownUserIdAnotherUserHasLinked() {
        link(bot1, Status.PENDING, STREAMER_ID);
        AnkamaFriendLink other = otherUsersLink(Status.ACCEPTED, "Else");
        other.setAnkamaUserId(STREAMER_ID);
        when(linkRepository.existsByUserIdNotAndStatusNotAndAnkamaUserId(user.getId(), Status.REMOVED, STREAMER_ID))
                .thenReturn(true);

        assertThatThrownBy(() -> service.enroll(user, "Enimaloc#6094"))
                .isInstanceOfSatisfying(AnkamaEnrollmentException.class, e ->
                        assertThat(e.getReason()).isEqualTo(AnkamaEnrollmentException.Reason.ALREADY_LINKED));
    }

    @Test
    void ambiguousEvent_withTwoMatchingLinks_bindsAndUpdatesNone() {
        AnkamaFriendLink first = link(bot1, Status.PENDING, null);
        AnkamaFriendLink second = otherUsersLink(Status.PENDING, "enimaloc");

        receive(bot1, new ChatMessages.FriendCreated(BOT_ID, STREAMER));
        receive(bot1, new ChatMessages.InviteCreated(new ChatMessages.Invite(BOT_USER, STREAMER)));

        assertThat(first.getStatus()).isEqualTo(Status.PENDING);
        assertThat(second.getStatus()).isEqualTo(Status.PENDING);
        assertThat(first.getAnkamaUserId()).isNull();
        assertThat(second.getAnkamaUserId()).isNull();
        verify(publisher, never()).publishAnkamaLinkChanged(any(), any());
        assertThat(getters).isEmpty();
    }

    @Test
    void lostAnswer_isGivenAgainWhenTheInviteIsReplayed() {
        link(bot1, Status.PENDING, null);
        when(sessionManager.request(any(), any())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("connection dropped")));
        ChatMessages.Event event = new ChatMessages.InviteCreated(new ChatMessages.Invite(STREAMER, BOT_USER));

        receive(bot1, event);
        receive(bot1, event);

        verify(sessionManager, org.mockito.Mockito.times(2)).request(eq(bot1.getId()), any());
    }

    @Test
    void failureAfterTheClaim_releasesTheKey() {
        link(bot1, Status.PENDING, null);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).doAnswer(inv -> inv.getArgument(0))
                .when(linkRepository).save(any());
        ChatMessages.Event event = new ChatMessages.InviteCreated(new ChatMessages.Invite(STREAMER, BOT_USER));

        receive(bot1, event);
        receive(bot1, event);

        verify(sessionManager).request(eq(bot1.getId()), any());
    }

    private static AnkamaServiceAccount bot(long ankamaId, int fillOrder, String nickname, String tag) {
        AnkamaServiceAccount bot = new AnkamaServiceAccount();
        bot.setId(UUID.randomUUID());
        bot.setAnkamaAccountId(ankamaId);
        bot.setFillOrder(fillOrder);
        bot.setNickname(nickname);
        bot.setTag(tag);
        bot.setAuthState(AuthState.READY);
        return bot;
    }

    private AnkamaFriendLink link(AnkamaServiceAccount bot, Status status, Long ankamaUserId) {
        AnkamaFriendLink link = new AnkamaFriendLink();
        link.setId(UUID.randomUUID());
        link.setUser(user);
        link.setServiceAccount(bot);
        link.setRequestedName("Enimaloc");
        link.setRequestedTag("6094");
        link.setAnkamaUserId(ankamaUserId);
        link.setStatus(status);
        links.add(link);
        return link;
    }

    private void receive(AnkamaServiceAccount bot, ChatMessages.Event event) {
        service.onFriendEvent(new AnkamaFriendEventReceived(bot.getId(), event));
    }

    private static ArgumentMatcher<ChatFrames.Payload> payloadOf(ChatMessages.Command command) {
        ChatFrames.Payload expected = command.toPayload();
        return actual -> actual.id() == expected.id() && Arrays.equals(actual.data(), expected.data());
    }

    private void verifyRequest(AnkamaServiceAccount bot, ChatMessages.Command command) {
        verify(sessionManager).request(eq(bot.getId()), argThat(payloadOf(command)));
    }

    private void verifyNoRequest(ChatMessages.Command command) {
        verify(sessionManager, never()).request(any(), argThat(payloadOf(command)));
    }

    // --- parse ---

    @Test
    void parse_trimsAndAcceptsDigitsTag() {
        assertThat(AnkamaFriendService.parse(" enimaloc #6094 "))
                .isEqualTo(new AnkamaFriendService.NameTag("enimaloc", "6094"));
    }

    @Test
    void parse_refusesMissingNameTagOrLetters() {
        for (String raw : new String[]{"enimaloc", "#6094", "enimaloc#60a4", "enimaloc#", null, "a#b#1"}) {
            assertThatThrownBy(() -> AnkamaFriendService.parse(raw)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- enroll ---

    @Test
    void enroll_picksTheFirstBotUnderTheCap_savesPending_thenInvites() {
        link(bot1, Status.ACCEPTED, 1L);
        link(bot1, Status.PENDING, null);
        // the cap is 2: bot1 is full

        AnkamaFriendLink link = service.enroll(user, "Enimaloc#6094");

        assertThat(link.getServiceAccount()).isSameAs(bot2);
        assertThat(link.getStatus()).isEqualTo(Status.PENDING);
        assertThat(link.getRequestedName()).isEqualTo("Enimaloc");
        assertThat(link.getRequestedTag()).isEqualTo("6094");
        verifyRequest(bot2, ChatMessages.invite(bot2.getAnkamaAccountId(), "Enimaloc", "6094"));
        verify(publisher).publishAnkamaLinkChanged(user.getId(),
                new AnkamaLinkStateResponse("PENDING", "Enimaloc#6094", "Bot#2222"));
    }

    @Test
    void enroll_withNoReadyBot_isNoAccountAvailable_withAllFull_isNoCapacity() {
        when(accountRepository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.enroll(user, "Enimaloc#6094"))
                .isInstanceOfSatisfying(AnkamaEnrollmentException.class, e ->
                        assertThat(e.getReason()).isEqualTo(AnkamaEnrollmentException.Reason.NO_ACCOUNT_AVAILABLE));

        when(accountRepository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of(bot1, bot2));
        bot1.setFriendLimitReached(true);
        link(bot2, Status.ACCEPTED, 1L);
        link(bot2, Status.INVITE_REJECTED, 2L);
        assertThatThrownBy(() -> service.enroll(user, "Enimaloc#6094"))
                .isInstanceOfSatisfying(AnkamaEnrollmentException.class, e ->
                        assertThat(e.getReason()).isEqualTo(AnkamaEnrollmentException.Reason.NO_CAPACITY));
    }

    @Test
    void enroll_replacesAnExistingLink() {
        AnkamaFriendLink old = link(bot2, Status.ACCEPTED, STREAMER_ID);

        AnkamaFriendLink link = service.enroll(user, "Enimaloc#6094");

        assertThat(links).containsExactly(link).doesNotContain(old);
        verifyRequest(bot2, ChatMessages.deleteFriend(bot2.getAnkamaAccountId(), STREAMER_ID));
        verify(publisher).publishAnkamaLinkChanged(user.getId(), new AnkamaLinkStateResponse("NONE", null, null));
    }

    @Test
    void enroll_withABadNameTag_changesNothing() {
        assertThatThrownBy(() -> service.enroll(user, "nope")).isInstanceOf(IllegalArgumentException.class);

        assertThat(links).isEmpty();
    }

    @Test
    void inviteFailure_keepsThePendingLink() {
        when(sessionManager.request(any(), any())).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("no connection")));

        AnkamaFriendLink link = service.enroll(user, "Enimaloc#6094");

        assertThat(links).containsExactly(link);
        assertThat(link.getStatus()).isEqualTo(Status.PENDING);
    }

    @Test
    void inviteThrowing_keepsThePendingLink() {
        when(sessionManager.request(any(), any())).thenThrow(new IllegalStateException("no session"));

        AnkamaFriendLink link = service.enroll(user, "Enimaloc#6094");

        assertThat(links).containsExactly(link);
    }

    @Test
    void resendInvite_onlyForAWaitingLink() {
        AnkamaFriendLink link = link(bot1, Status.ACCEPTED, STREAMER_ID);
        service.resendInvite(user);
        verifyNoRequest(ChatMessages.invite(BOT_ID, "Enimaloc", "6094"));

        link.setStatus(Status.INVITE_REJECTED);
        service.resendInvite(user);
        verifyRequest(bot1, ChatMessages.invite(BOT_ID, "Enimaloc", "6094"));
    }

    // --- bot events ---

    @Test
    void botInviteCreated_learnsTheRecipientUserId() {
        AnkamaFriendLink link = link(bot1, Status.PENDING, null);

        receive(bot1, new ChatMessages.InviteCreated(new ChatMessages.Invite(BOT_USER, STREAMER)));

        assertThat(link.getAnkamaUserId()).isEqualTo(STREAMER_ID);
        assertThat(link.getStatus()).isEqualTo(Status.PENDING);
        verify(publisher, never()).publishAnkamaLinkChanged(any(), any());
    }

    @Test
    void streamerInvitesTheBot_matchingPendingLink_isAccepted() {
        AnkamaFriendLink link = link(bot1, Status.PENDING, null);

        receive(bot1, new ChatMessages.InviteCreated(new ChatMessages.Invite(STREAMER, BOT_USER)));

        assertThat(link.getAnkamaUserId()).isEqualTo(STREAMER_ID);
        verifyRequest(bot1, ChatMessages.deleteInvite(STREAMER_ID, BOT_ID, ChatMessages.INVITE_ACCEPT));
    }

    @Test
    void streamerInvitesTheBot_afterRejecting_isAccepted() {
        link(bot1, Status.INVITE_REJECTED, STREAMER_ID);

        receive(bot1, new ChatMessages.InviteCreated(new ChatMessages.Invite(STREAMER, BOT_USER)));

        verifyRequest(bot1, ChatMessages.deleteInvite(STREAMER_ID, BOT_ID, ChatMessages.INVITE_ACCEPT));
    }

    @Test
    void streamerInvitesTheBot_replayedByTheResync_isAnsweredOnce() {
        link(bot1, Status.PENDING, null);
        ChatMessages.Event event = new ChatMessages.InviteCreated(new ChatMessages.Invite(STREAMER, BOT_USER));

        receive(bot1, event);
        receive(bot1, event);

        verify(sessionManager).request(eq(bot1.getId()), any());
    }

    @Test
    void strangerInvitesTheBot_isRejected() {
        link(bot1, Status.PENDING, null);
        ChatMessages.User stranger = new ChatMessages.User(9999L, "Someone", "1");

        receive(bot1, new ChatMessages.InviteCreated(new ChatMessages.Invite(stranger, BOT_USER)));

        verifyRequest(bot1, ChatMessages.deleteInvite(9999L, BOT_ID, ChatMessages.INVITE_REJECT));
    }

    @Test
    void inviteFromAnAlreadyAcceptedFriend_isLeftAlone() {
        link(bot1, Status.ACCEPTED, STREAMER_ID);

        receive(bot1, new ChatMessages.InviteCreated(new ChatMessages.Invite(STREAMER, BOT_USER)));

        verify(sessionManager, never()).request(any(), any());
    }

    @Test
    void invitationRejected_setsInviteRejected() {
        AnkamaFriendLink link = link(bot1, Status.PENDING, STREAMER_ID);

        receive(bot1, new ChatMessages.InviteDeleted(new ChatMessages.Invite(BOT_USER, STREAMER),
                ChatMessages.INVITE_REJECT));

        assertThat(link.getStatus()).isEqualTo(Status.INVITE_REJECTED);
        verify(publisher).publishAnkamaLinkChanged(user.getId(),
                new AnkamaLinkStateResponse("INVITE_REJECTED", "Enimaloc#6094", "Bot#1111"));
    }

    @Test
    void invitationAcceptedOrCancelled_doesNotRejectTheLink() {
        AnkamaFriendLink link = link(bot1, Status.PENDING, STREAMER_ID);

        receive(bot1, new ChatMessages.InviteDeleted(new ChatMessages.Invite(BOT_USER, STREAMER),
                ChatMessages.INVITE_ACCEPT));

        assertThat(link.getStatus()).isEqualTo(Status.PENDING);
    }

    @Test
    void friendCreated_setsAccepted_andAddsTheAnkamaGetter() {
        AnkamaFriendLink link = link(bot1, Status.PENDING, STREAMER_ID);

        receive(bot1, new ChatMessages.FriendCreated(BOT_ID, STREAMER));

        assertThat(link.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(link.getAcceptedAt()).isNotNull();
        assertThat(getters).singleElement().satisfies(config -> {
            assertThat(config.getProvider()).isEqualTo(GetterConfig.Provider.ANKAMA);
            assertThat(config.isEnabled()).isTrue();
        });
    }

    @Test
    void friendCreated_matchesByNameTagCaseInsensitively_whenTheUserIdIsUnknown() {
        AnkamaFriendLink link = link(bot1, Status.PENDING, null);
        link.setRequestedName("ENIMALOC");

        receive(bot1, new ChatMessages.FriendCreated(BOT_ID, STREAMER));

        assertThat(link.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(link.getAnkamaUserId()).isEqualTo(STREAMER_ID);
    }

    @Test
    void friendCreated_replayedForAnAcceptedLink_changesNothing() {
        link(bot1, Status.ACCEPTED, STREAMER_ID);

        receive(bot1, new ChatMessages.FriendCreated(BOT_ID, STREAMER));

        verify(publisher, never()).publishAnkamaLinkChanged(any(), any());
        assertThat(getters).isEmpty();
    }

    @Test
    void friendDeleted_setsRemoved() {
        AnkamaFriendLink link = link(bot1, Status.ACCEPTED, STREAMER_ID);

        receive(bot1, new ChatMessages.FriendDeleted(BOT_ID, STREAMER_ID));

        assertThat(link.getStatus()).isEqualTo(Status.REMOVED);
        verify(publisher).publishAnkamaLinkChanged(user.getId(),
                new AnkamaLinkStateResponse("REMOVED", "Enimaloc#6094", null));
    }

    @Test
    void friendDeleted_ofAPendingLink_isIgnored() {
        AnkamaFriendLink link = link(bot1, Status.PENDING, STREAMER_ID);

        receive(bot1, new ChatMessages.FriendDeleted(BOT_ID, STREAMER_ID));

        assertThat(link.getStatus()).isEqualTo(Status.PENDING);
    }

    @Test
    void eventWithNullUsers_isIgnored() {
        link(bot1, Status.PENDING, null);

        receive(bot1, new ChatMessages.InviteCreated(new ChatMessages.Invite(null, null)));
        receive(bot1, new ChatMessages.FriendCreated(BOT_ID, null));

        verify(sessionManager, never()).request(any(), any());
    }

    // --- unenroll ---

    @Test
    void unenroll_deletesTheFriend_orCancelsThePendingInvite() {
        AnkamaFriendLink link = link(bot1, Status.ACCEPTED, STREAMER_ID);
        service.unenroll(user);
        verifyRequest(bot1, ChatMessages.deleteFriend(BOT_ID, STREAMER_ID));
        assertThat(links).isEmpty();

        links.add(link);
        link.setStatus(Status.PENDING);
        service.unenroll(user);
        verifyRequest(bot1, ChatMessages.deleteInvite(BOT_ID, STREAMER_ID, ChatMessages.INVITE_CANCEL));
        assertThat(links).isEmpty();
    }

    @Test
    void unenroll_withoutAKnownUserId_sendsNothing_andDisablesTheGetter() {
        link(bot1, Status.PENDING, null);
        GetterConfig config = new GetterConfig();
        config.setProvider(GetterConfig.Provider.ANKAMA);
        config.setEnabled(true);
        getters.add(config);

        service.unenroll(user);

        verify(sessionManager, never()).request(any(), any());
        assertThat(config.isEnabled()).isFalse();
        assertThat(links).isEmpty();
    }

    @Test
    void everyStatusChange_publishesTheLinkState() {
        AnkamaFriendLink link = link(bot1, Status.PENDING, STREAMER_ID);

        receive(bot1, new ChatMessages.FriendCreated(BOT_ID, STREAMER));
        receive(bot1, new ChatMessages.FriendDeleted(BOT_ID, STREAMER_ID));
        service.unenroll(user);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(publisher);
        order.verify(publisher).publishAnkamaLinkChanged(user.getId(),
                new AnkamaLinkStateResponse("ACCEPTED", "Enimaloc#6094", null));
        order.verify(publisher).publishAnkamaLinkChanged(user.getId(),
                new AnkamaLinkStateResponse("REMOVED", "Enimaloc#6094", null));
        order.verify(publisher).publishAnkamaLinkChanged(user.getId(),
                new AnkamaLinkStateResponse("NONE", null, null));
    }
}
