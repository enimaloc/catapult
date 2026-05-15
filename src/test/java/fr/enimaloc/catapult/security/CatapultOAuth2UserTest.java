package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatapultOAuth2UserTest {

    private CatapultOAuth2User buildUser(boolean admin) {
        OAuth2User delegate = mock(OAuth2User.class);
        when(delegate.getAttributes()).thenReturn(Map.of("login", "testuser"));
        UserAccount account = new UserAccount();
        account.setTwitchId("123");
        account.setTwitchUsername("testuser");
        account.setStatus(UserAccount.Status.ACTIVE);
        return new CatapultOAuth2User(delegate, account, admin);
    }

    @Test
    void regularUser_hasOnlyRoleUser() {
        CatapultOAuth2User user = buildUser(false);
        Set<String> roles = user.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        assertThat(roles).containsExactly("ROLE_USER");
    }

    @Test
    void adminUser_hasBothRoles() {
        CatapultOAuth2User user = buildUser(true);
        Set<String> roles = user.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        assertThat(roles).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void getName_returnsTwitchId() {
        CatapultOAuth2User user = buildUser(false);
        assertThat(user.getName()).isEqualTo("123");
    }

    // --- UserDetails contract ---

    @Test
    void getUsername_returnsTwitchUsername() {
        CatapultOAuth2User user = buildUser(false);
        assertThat(user.getUsername()).isEqualTo("testuser");
    }

    @Test
    void isEnabled_trueWhenActive() {
        CatapultOAuth2User user = buildUser(false);
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void isAccountNonLocked_falseWhenPendingDeletion() {
        OAuth2User delegate = mock(OAuth2User.class);
        UserAccount account = new UserAccount();
        account.setTwitchId("456");
        account.setTwitchUsername("deleteme");
        account.setStatus(UserAccount.Status.PENDING_DELETION);
        CatapultOAuth2User user = new CatapultOAuth2User(delegate, account, false);
        assertThat(user.isAccountNonLocked()).isFalse();
    }

    // --- forImpersonation factory ---

    @Test
    void forImpersonation_nullDelegate_getAttributesReturnsEmpty() {
        UserAccount account = new UserAccount();
        account.setTwitchId("789");
        account.setTwitchUsername("impersonated");
        account.setStatus(UserAccount.Status.ACTIVE);

        CatapultOAuth2User user = CatapultOAuth2User.forImpersonation(account, false);

        assertThat(user.getAttributes()).isEmpty();
        assertThat(user.getUsername()).isEqualTo("impersonated");
        assertThat(user.getName()).isEqualTo("789");
    }

    @Test
    void forImpersonation_adminFlag_grantsAdminRole() {
        UserAccount account = new UserAccount();
        account.setTwitchId("owner");
        account.setTwitchUsername("theowner");
        account.setStatus(UserAccount.Status.ACTIVE);

        CatapultOAuth2User user = CatapultOAuth2User.forImpersonation(account, true);

        Set<String> roles = user.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        assertThat(roles).contains("ROLE_ADMIN");
    }
}
