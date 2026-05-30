package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImpersonationUserDetailsServiceTest {

    @Mock
    private UserAccountRepository userAccountRepository;

    @InjectMocks
    private ImpersonationUserDetailsService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "ownerId", "owner123");
    }

    @Test
    void loadUserByUsername_returnsUserDetails() {
        UserAccount account = new UserAccount();
        account.setTwitchId("user456");
        account.setTwitchUsername("testuser");
        account.setStatus(UserAccount.Status.ACTIVE);
        when(userAccountRepository.findByTwitchUsername("testuser")).thenReturn(Optional.of(account));

        UserDetails details = service.loadUserByUsername("testuser");

        assertThat(details.getUsername()).isEqualTo("testuser");
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void loadUserByUsername_nonOwner_doesNotGetAdminRole() {
        UserAccount account = new UserAccount();
        account.setTwitchId("user456");
        account.setTwitchUsername("testuser");
        account.setStatus(UserAccount.Status.ACTIVE);
        when(userAccountRepository.findByTwitchUsername("testuser")).thenReturn(Optional.of(account));

        UserDetails details = service.loadUserByUsername("testuser");

        Set<String> roles = details.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        assertThat(roles).doesNotContain("ROLE_ADMIN");
    }

    @Test
    void loadUserByUsername_ownerGetsAdminRole() {
        UserAccount account = new UserAccount();
        account.setTwitchId("owner123");
        account.setTwitchUsername("theowner");
        account.setStatus(UserAccount.Status.ACTIVE);
        when(userAccountRepository.findByTwitchUsername("theowner")).thenReturn(Optional.of(account));

        UserDetails details = service.loadUserByUsername("theowner");

        Set<String> roles = details.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        assertThat(roles).contains("ROLE_ADMIN");
    }

    @Test
    void loadUserByUsername_unknownUser_throwsUsernameNotFoundException() {
        when(userAccountRepository.findByTwitchUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("ghost"))
            .isInstanceOf(UsernameNotFoundException.class)
            .hasMessageContaining("ghost");
    }
}
