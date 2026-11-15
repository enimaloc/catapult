package fr.enimaloc.catapult.service.epic;

import fr.enimaloc.catapult.common.dto.connect.EpicLinkStateResponse;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GetterConfig;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink;
import fr.enimaloc.catapult.domain.epic.EpicFriendLink.Status;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount;
import fr.enimaloc.catapult.domain.epic.EpicServiceAccount.AuthState;
import fr.enimaloc.catapult.repository.binding.GetterConfigRepository;
import fr.enimaloc.catapult.repository.epic.EpicFriendLinkRepository;
import fr.enimaloc.catapult.repository.epic.EpicServiceAccountRepository;
import fr.enimaloc.catapult.service.epic.EpicEnrollmentException.Reason;
import fr.enimaloc.catapult.service.epic.api.EpicAuthException;
import fr.enimaloc.catapult.service.epic.api.EpicFriendsClient;
import fr.enimaloc.catapult.service.epic.api.EpicFriendsClient.AddResult;
import fr.enimaloc.catapult.service.epic.api.EpicFriendsClient.Summary;
import fr.enimaloc.catapult.service.notification.ChannelEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionOperations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EpicFriendServiceTest {

    private static final String BOT_EPIC_ID = "bot-epic-id";
    private static final String STREAMER_EPIC_ID = "streamer-epic-id";

    @Mock private EpicServiceAccountRepository accountRepository;
    @Mock private EpicFriendLinkRepository linkRepository;
    @Mock private GetterConfigRepository getterConfigRepository;
    @Mock private ChannelEventPublisher publisher;
    @Mock private EpicTokenService tokenService;
    @Mock private EpicFriendsClient friendsClient;

    private EpicFriendService service;
    private UserAccount user;
    private EpicServiceAccount bot1;
    private EpicServiceAccount bot2;
    private final List<EpicFriendLink> links = new ArrayList<>();
    private final List<GetterConfig> getters = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new EpicFriendService(accountRepository, linkRepository, getterConfigRepository, publisher,
                tokenService, friendsClient, TransactionOperations.withoutTransaction(), 2);
        user = user();
        bot1 = bot(1, BOT_EPIC_ID);
        bot2 = bot(2, "bot2-epic-id");

        when(accountRepository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of(bot1, bot2));
        when(accountRepository.findById(bot1.getId())).thenReturn(Optional.of(bot1));
        when(accountRepository.findById(bot2.getId())).thenReturn(Optional.of(bot2));
        when(accountRepository.findByEpicAccountId(anyString())).thenReturn(Optional.empty());
        when(accountRepository.findByEpicAccountId(BOT_EPIC_ID)).thenReturn(Optional.of(bot1));
        when(tokenService.accessToken(any())).thenReturn("token");
        when(friendsClient.add(any(), any(), any())).thenReturn(AddResult.SENT);

        when(linkRepository.save(any())).thenAnswer(inv -> {
            EpicFriendLink link = inv.getArgument(0);
            if (link.getId() == null) link.setId(UUID.randomUUID());
            if (!links.contains(link)) links.add(link);
            return link;
        });
        when(linkRepository.findById(any())).thenAnswer(inv -> links.stream()
                .filter(link -> link.getId().equals(inv.getArgument(0))).findFirst());
        when(linkRepository.findWithServiceAccountByUser(any())).thenAnswer(inv -> links.stream()
                .filter(link -> link.getUser() == inv.getArgument(0)).findFirst());
        doAnswer(inv -> links.remove((EpicFriendLink) inv.getArgument(0))).when(linkRepository).delete(any());
        doAnswer(inv -> links.removeAll((Collection<?>) inv.getArgument(0))).when(linkRepository).deleteAll(any());
        when(linkRepository.findByEpicAccountIdAndStatusNot(any(), any())).thenAnswer(inv -> links.stream()
                .filter(link -> link.getEpicAccountId().equals(inv.getArgument(0))
                        && link.getStatus() != inv.getArgument(1)).toList());
        when(linkRepository.findByEpicAccountIdAndStatus(any(), any())).thenAnswer(inv -> links.stream()
                .filter(link -> link.getEpicAccountId().equals(inv.getArgument(0))
                        && link.getStatus() == inv.getArgument(1)).toList());
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
    }

    private static UserAccount user() {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        return account;
    }

    private static EpicServiceAccount bot(int fillOrder, String epicId) {
        EpicServiceAccount bot = new EpicServiceAccount();
        bot.setId(UUID.randomUUID());
        bot.setEpicAccountId(epicId);
        bot.setDisplayName("Bot" + fillOrder);
        bot.setFillOrder(fillOrder);
        return bot;
    }

    private EpicFriendLink link(UserAccount owner, EpicServiceAccount bot, String epicId, Status status) {
        EpicFriendLink link = new EpicFriendLink();
        link.setId(UUID.randomUUID());
        link.setUser(owner);
        link.setServiceAccount(bot);
        link.setEpicAccountId(epicId);
        link.setDisplayName("Name-" + epicId);
        link.setStatus(status);
        links.add(link);
        return link;
    }

    private static Summary summary(Set<String> friends, Set<String> incoming, Set<String> outgoing) {
        return new Summary(friends, incoming, outgoing);
    }

    private void sync(EpicServiceAccount bot, Summary summary) {
        service.onFriendsSynced(new EpicFriendEventReceived(bot.getId(), summary));
    }

    // --- enroll ---

    @Test
    void enroll_picksTheFirstBotUnderTheCap_savesPending_thenSendsTheRequest() {
        link(user(), bot1, "other-1", Status.ACCEPTED);
        link(user(), bot1, "other-2", Status.PENDING);

        EpicFriendLink created = service.enroll(user, STREAMER_EPIC_ID, "Streamer");

        assertThat(created.getServiceAccount()).isSameAs(bot2);
        assertThat(created.getStatus()).isEqualTo(Status.PENDING);
        assertThat(created.getEpicAccountId()).isEqualTo(STREAMER_EPIC_ID);
        assertThat(created.getDisplayName()).isEqualTo("Streamer");
        var order = inOrder(linkRepository, friendsClient);
        order.verify(linkRepository).save(any());
        order.verify(friendsClient).add("token", "bot2-epic-id", STREAMER_EPIC_ID);
    }

    @Test
    void enroll_alreadyFriends_isAcceptedAtOnce_andAddsTheGetter() {
        when(friendsClient.add(any(), any(), any())).thenReturn(AddResult.ALREADY_FRIENDS);

        EpicFriendLink created = service.enroll(user, STREAMER_EPIC_ID, "Streamer");

        assertThat(created.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(created.getAcceptedAt()).isNotNull();
        assertThat(getters).singleElement().satisfies(config -> {
            assertThat(config.getProvider()).isEqualTo(GetterConfig.Provider.EPIC);
            assertThat(config.isEnabled()).isTrue();
        });
        verify(publisher).publishEpicLinkChanged(eq(user.getId()),
                eq(new EpicLinkStateResponse("PENDING", "Streamer", "Bot1")));
        verify(publisher).publishEpicLinkChanged(eq(user.getId()),
                eq(new EpicLinkStateResponse("ACCEPTED", "Streamer", null)));
    }

    @Test
    void enroll_accountLinkedToAnotherMember_isAlreadyLinked() {
        link(user(), bot1, STREAMER_EPIC_ID, Status.ACCEPTED);

        assertThatThrownBy(() -> service.enroll(user, STREAMER_EPIC_ID, "Streamer"))
                .isInstanceOfSatisfying(EpicEnrollmentException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.ALREADY_LINKED));
        verify(friendsClient, never()).add(any(), any(), any());
    }

    @Test
    void enroll_accountOfABot_isAlreadyLinked() {
        assertThatThrownBy(() -> service.enroll(user, BOT_EPIC_ID, "A bot"))
                .isInstanceOfSatisfying(EpicEnrollmentException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.ALREADY_LINKED));
        verify(linkRepository, never()).save(any());
    }

    @Test
    void enroll_aStaleRemovedLinkOfAnotherMember_isDeleted_andDoesNotBlock() {
        EpicFriendLink stale = link(user(), bot1, STREAMER_EPIC_ID, Status.REMOVED);

        EpicFriendLink created = service.enroll(user, STREAMER_EPIC_ID, "Streamer");

        assertThat(links).containsExactly(created).doesNotContain(stale);
    }

    @Test
    void enroll_noReadyBot_isNoAccountAvailable_allFull_isNoCapacity() {
        when(accountRepository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of());
        assertThatThrownBy(() -> service.enroll(user, STREAMER_EPIC_ID, "Streamer"))
                .isInstanceOfSatisfying(EpicEnrollmentException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.NO_ACCOUNT_AVAILABLE));

        when(accountRepository.findByEnabledTrueAndAuthStateOrderByFillOrderAsc(AuthState.READY))
                .thenReturn(List.of(bot1));
        bot1.setFriendLimitReached(true);
        assertThatThrownBy(() -> service.enroll(user, STREAMER_EPIC_ID, "Streamer"))
                .isInstanceOfSatisfying(EpicEnrollmentException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.NO_CAPACITY));

        bot1.setFriendLimitReached(false);
        link(user(), bot1, "a", Status.ACCEPTED);
        link(user(), bot1, "b", Status.ACCEPTED);
        assertThatThrownBy(() -> service.enroll(user, STREAMER_EPIC_ID, "Streamer"))
                .isInstanceOfSatisfying(EpicEnrollmentException.class,
                        e -> assertThat(e.getReason()).isEqualTo(Reason.NO_CAPACITY));
    }

    @Test
    void enroll_replacesTheMembersPreviousLink() {
        EpicFriendLink previous = link(user, bot1, "previous-id", Status.ACCEPTED);

        EpicFriendLink created = service.enroll(user, STREAMER_EPIC_ID, "Streamer");

        verify(friendsClient).delete("token", BOT_EPIC_ID, "previous-id");
        assertThat(links).containsExactly(created).doesNotContain(previous);
    }

    @Test
    void enroll_aRejectedAccessToken_isEvictedAndTheRequestRetriedOnce() {
        when(friendsClient.add(any(), any(), any()))
                .thenThrow(new EpicAuthException(EpicAuthException.Reason.ACCESS_REJECTED))
                .thenReturn(AddResult.SENT);

        service.enroll(user, STREAMER_EPIC_ID, "Streamer");

        verify(tokenService).evict(bot1.getId());
        verify(friendsClient, times(2)).add(any(), any(), any());
    }

    @Test
    void requestFailure_keepsThePendingLink() {
        when(friendsClient.add(any(), any(), any())).thenThrow(new IllegalStateException("down"));

        EpicFriendLink created = service.enroll(user, STREAMER_EPIC_ID, "Streamer");

        assertThat(created.getStatus()).isEqualTo(Status.PENDING);
        assertThat(links).containsExactly(created);
    }

    @Test
    void aBotNeedingANewLogin_keepsThePendingLink() {
        when(tokenService.accessToken(any()))
                .thenThrow(new EpicAuthException(EpicAuthException.Reason.REFRESH_REFUSED));

        EpicFriendLink created = service.enroll(user, STREAMER_EPIC_ID, "Streamer");

        assertThat(created.getStatus()).isEqualTo(Status.PENDING);
        verify(tokenService, never()).evict(any());
    }

    // --- resend ---

    @Test
    void resendInvite_onlyForAWaitingLink() {
        EpicFriendLink link = link(user, bot1, STREAMER_EPIC_ID, Status.INVITE_REJECTED);
        service.resendInvite(user);
        verify(friendsClient).add("token", BOT_EPIC_ID, STREAMER_EPIC_ID);

        link.setStatus(Status.ACCEPTED);
        service.resendInvite(user);
        verify(friendsClient, times(1)).add(any(), any(), any());
    }

    // --- sync ---

    @Test
    void sync_friend_setsAccepted() {
        EpicFriendLink link = link(user, bot1, STREAMER_EPIC_ID, Status.PENDING);

        sync(bot1, summary(Set.of(STREAMER_EPIC_ID), Set.of(), Set.of()));

        assertThat(link.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(link.getAcceptedAt()).isNotNull();
        assertThat(getters).singleElement().satisfies(
                config -> assertThat(config.getProvider()).isEqualTo(GetterConfig.Provider.EPIC));
        verify(publisher).publishEpicLinkChanged(user.getId(),
                new EpicLinkStateResponse("ACCEPTED", "Name-" + STREAMER_EPIC_ID, null));
    }

    @Test
    void sync_requestGone_setsInviteRejected() {
        EpicFriendLink link = link(user, bot1, STREAMER_EPIC_ID, Status.PENDING);

        sync(bot1, summary(Set.of(), Set.of(), Set.of()));

        assertThat(link.getStatus()).isEqualTo(Status.INVITE_REJECTED);
        verify(publisher).publishEpicLinkChanged(eq(user.getId()),
                eq(new EpicLinkStateResponse("INVITE_REJECTED", "Name-" + STREAMER_EPIC_ID, "Bot1")));
    }

    @Test
    void sync_requestStillOutgoing_keepsPending_andAnOutgoingOneRevivesARejectedLink() {
        EpicFriendLink pending = link(user, bot1, STREAMER_EPIC_ID, Status.PENDING);
        EpicFriendLink rejected = link(user(), bot1, "other-id", Status.INVITE_REJECTED);

        sync(bot1, summary(Set.of(), Set.of(), Set.of(STREAMER_EPIC_ID, "other-id")));

        assertThat(pending.getStatus()).isEqualTo(Status.PENDING);
        assertThat(rejected.getStatus()).isEqualTo(Status.PENDING);
        verify(publisher, times(1)).publishEpicLinkChanged(any(), any());
    }

    @Test
    void sync_friendGone_setsRemoved() {
        EpicFriendLink link = link(user, bot1, STREAMER_EPIC_ID, Status.ACCEPTED);

        sync(bot1, summary(Set.of(), Set.of(), Set.of()));

        assertThat(link.getStatus()).isEqualTo(Status.REMOVED);
        verify(publisher).publishEpicLinkChanged(eq(user.getId()),
                eq(new EpicLinkStateResponse("REMOVED", "Name-" + STREAMER_EPIC_ID, null)));
    }

    @Test
    void sync_aSnapshotChangingNothing_publishesNothing() {
        link(user, bot1, STREAMER_EPIC_ID, Status.ACCEPTED);

        sync(bot1, summary(Set.of(STREAMER_EPIC_ID), Set.of(), Set.of()));

        verify(publisher, never()).publishEpicLinkChanged(any(), any());
    }

    @Test
    void sync_incomingFromAPendingStreamer_isAccepted_fromAStranger_isRefused() {
        EpicFriendLink link = link(user, bot1, STREAMER_EPIC_ID, Status.PENDING);

        sync(bot1, summary(Set.of(), Set.of(STREAMER_EPIC_ID, "stranger"), Set.of()));

        verify(friendsClient).add("token", BOT_EPIC_ID, STREAMER_EPIC_ID);
        verify(friendsClient).delete("token", BOT_EPIC_ID, "stranger");
        verify(friendsClient, never()).delete(any(), any(), eq(STREAMER_EPIC_ID));
        // the streamer's own request is as alive as the bot's: the link waits for the friendship
        assertThat(link.getStatus()).isEqualTo(Status.PENDING);
    }

    @Test
    void sync_incomingFromAStreamerLinkedToAnotherBot_isRefused() {
        link(user, bot2, STREAMER_EPIC_ID, Status.PENDING);

        sync(bot1, summary(Set.of(), Set.of(STREAMER_EPIC_ID), Set.of()));

        verify(friendsClient).delete("token", BOT_EPIC_ID, STREAMER_EPIC_ID);
        verify(friendsClient, never()).add(any(), any(), any());
    }

    @Test
    void sync_aRemovedLink_staysRemoved() {
        EpicFriendLink link = link(user, bot1, STREAMER_EPIC_ID, Status.REMOVED);

        sync(bot1, summary(Set.of(STREAMER_EPIC_ID), Set.of(), Set.of()));

        assertThat(link.getStatus()).isEqualTo(Status.REMOVED);
        verify(publisher, never()).publishEpicLinkChanged(any(), any());
    }

    @Test
    void sync_aFailingRefusal_isLoggedNotThrown() {
        doThrow(new IllegalStateException("down")).when(friendsClient).delete(any(), any(), any());

        sync(bot1, summary(Set.of(), Set.of("stranger"), Set.of()));

        verify(friendsClient).delete("token", BOT_EPIC_ID, "stranger");
    }

    // --- unenroll ---

    @Test
    void unenroll_deletesTheFriendship_andTheLink() {
        link(user, bot1, STREAMER_EPIC_ID, Status.ACCEPTED);
        GetterConfig getter = new GetterConfig();
        getter.setProvider(GetterConfig.Provider.EPIC);
        getter.setEnabled(true);
        getters.add(getter);

        service.unenroll(user);

        var order = inOrder(friendsClient, linkRepository);
        order.verify(friendsClient).delete("token", BOT_EPIC_ID, STREAMER_EPIC_ID);
        order.verify(linkRepository).delete(any());
        assertThat(links).isEmpty();
        assertThat(getter.isEnabled()).isFalse();
        verify(publisher).publishEpicLinkChanged(user.getId(), new EpicLinkStateResponse("NONE", null, null));
    }

    @Test
    void unenroll_dropsTheLinkEvenWhenEpicFails() {
        link(user, bot1, STREAMER_EPIC_ID, Status.PENDING);
        doThrow(new IllegalStateException("down")).when(friendsClient).delete(any(), any(), any());

        service.unenroll(user);

        assertThat(links).isEmpty();
    }

    @Test
    void unenroll_withoutALink_doesNothing() {
        service.unenroll(user);

        verify(publisher, never()).publishEpicLinkChanged(any(), any());
        verify(friendsClient, never()).delete(any(), any(), any());
    }

    @Test
    void getLink_returnsTheMembersLink() {
        EpicFriendLink link = link(user, bot1, STREAMER_EPIC_ID, Status.PENDING);
        assertThat(service.getLink(user)).containsSame(link);
    }

    // --- publishing ---

    @Test
    void everyStatusChange_publishesTheLinkState() {
        EpicFriendLink link = link(user, bot1, STREAMER_EPIC_ID, Status.PENDING);

        sync(bot1, summary(Set.of(), Set.of(), Set.of()));
        sync(bot1, summary(Set.of(STREAMER_EPIC_ID), Set.of(), Set.of()));
        sync(bot1, summary(Set.of(), Set.of(), Set.of()));
        service.unenroll(user);

        var order = inOrder(publisher);
        for (String status : List.of("INVITE_REJECTED", "ACCEPTED", "REMOVED", "NONE")) {
            order.verify(publisher).publishEpicLinkChanged(eq(user.getId()),
                    org.mockito.ArgumentMatchers.argThat(state -> state.status().equals(status)));
        }
        assertThat(link.getStatus()).isEqualTo(Status.REMOVED);
    }
}
