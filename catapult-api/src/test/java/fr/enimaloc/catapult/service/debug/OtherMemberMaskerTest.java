package fr.enimaloc.catapult.service.debug;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.minecraft.MinecraftFriendLink;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.repository.minecraft.MinecraftFriendLinkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OtherMemberMaskerTest {

    private final UUID aId = UUID.randomUUID();
    private final UUID bId = UUID.randomUUID();
    private OtherMemberMasker masker;

    private UserAccount user(UUID id, String twitchId, String login, String steamId) {
        UserAccount u = new UserAccount();
        u.setId(id);
        u.setTwitchId(twitchId);
        u.setTwitchUsername(login);
        u.setSteamId(steamId);
        return u;
    }

    @BeforeEach
    void setUp() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        MinecraftFriendLinkRepository links = mock(MinecraftFriendLinkRepository.class);
        UserAccount a = user(aId, "1111", "alice", "76561190000000001");
        UserAccount b = user(bId, "2222222", "BobTheBuilder", "76561190000000002");
        when(users.findAll()).thenReturn(List.of(a, b));
        MinecraftFriendLink link = new MinecraftFriendLink();
        link.setUser(b);
        link.setMinecraftProfileId("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        link.setMinecraftName("Notch");
        when(links.findAll()).thenReturn(List.of(link));
        masker = new OtherMemberMasker(users, links);
        masker.reload();
    }

    @Test
    void otherMembersAreMasked_subjectIsNot() {
        String text = "alice 1111 76561190000000001 bobthebuilder 2222222 76561190000000002 Notch "
                + "069a79f444e94726a5befca90e38aaf5 069a79f4-44e9-4726-a5be-fca90e38aaf5";
        assertThat(masker.mask(text, aId)).isEqualTo(
                "alice 1111 76561190000000001 [membre] [membre] [membre] [membre] [membre] [membre]");
    }

    @Test
    void longerIdContainingAMemberId_isNotCut() {
        assertThat(masker.mask("x22222223 and 2222222a and (2222222)", aId)).isEqualTo("x22222223 and 2222222a and ([membre])");
    }

    @Test
    void shortIdentifiersAreIgnored() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.findAll()).thenReturn(List.of(user(bId, "12", "abc", null)));
        OtherMemberMasker m = new OtherMemberMasker(users, mock(MinecraftFriendLinkRepository.class));
        m.reload();
        assertThat(m.mask("12 abc", aId)).isEqualTo("12 abc");
    }

    @Test
    void nullAndEmpty_areSafe() {
        assertThat(masker.mask(null, aId)).isNull();
        assertThat(masker.mask("", aId)).isEmpty();
    }

    @Test
    void underscoreIsPartOfAWord() {
        assertThat(masker.mask("bobthebuilder_x x_bobthebuilder", aId)).isEqualTo("bobthebuilder_x x_bobthebuilder");
    }

    @Test
    void collidingIdentifier_isMaskedUnlessOnlySubjectOwnsIt() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        MinecraftFriendLinkRepository links = mock(MinecraftFriendLinkRepository.class);
        UserAccount b = user(bId, "2222222", "shared_name", null);
        UserAccount a = user(aId, "1111", "alice", null);
        when(users.findAll()).thenReturn(List.of(a, b));
        MinecraftFriendLink link = new MinecraftFriendLink();
        link.setUser(a);
        link.setMinecraftProfileId("069a79f4-44e9-4726-a5be-fca90e38aaf5");
        link.setMinecraftName("Shared_Name");
        when(links.findAll()).thenReturn(List.of(link));
        OtherMemberMasker m = new OtherMemberMasker(users, links);
        m.reload();
        assertThat(m.mask("shared_name", aId)).isEqualTo("[membre]");
    }

    @Test
    void accountCreatedListener_isAfterCommit() throws Exception {
        var tel = OtherMemberMasker.class.getMethod("onAccountCreated", fr.enimaloc.catapult.event.AccountCreatedEvent.class)
                .getAnnotation(org.springframework.transaction.event.TransactionalEventListener.class);
        assertThat(tel).isNotNull();
        assertThat(tel.phase()).isEqualTo(org.springframework.transaction.event.TransactionPhase.AFTER_COMMIT);
        assertThat(tel.fallbackExecution()).isTrue();
    }
}
