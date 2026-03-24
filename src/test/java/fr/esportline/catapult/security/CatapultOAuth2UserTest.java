package fr.esportline.catapult.security;

import fr.esportline.catapult.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

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
        return new CatapultOAuth2User(delegate, account, admin);
    }

    @Test
    void regularUser_hasOnlyRoleUser() {
        CatapultOAuth2User user = buildUser(false);
        Collection<? extends GrantedAuthority> authorities = user.getAuthorities();
        Set<String> roles = authorities.stream().map(GrantedAuthority::getAuthority).collect(java.util.stream.Collectors.toSet());
        assertThat(roles).containsExactly("ROLE_USER");
    }

    @Test
    void adminUser_hasBothRoles() {
        CatapultOAuth2User user = buildUser(true);
        Collection<? extends GrantedAuthority> authorities = user.getAuthorities();
        Set<String> roles = authorities.stream().map(GrantedAuthority::getAuthority).collect(java.util.stream.Collectors.toSet());
        assertThat(roles).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void getName_returnsTwitchId() {
        CatapultOAuth2User user = buildUser(false);
        assertThat(user.getName()).isEqualTo("123");
    }
}
