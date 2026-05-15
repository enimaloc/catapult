package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.UserAccount;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Wrapper OAuth2User qui expose le UserAccount applicatif.
 * ROLE_ADMIN est accordé si le twitchId correspond à l'owner configuré.
 * Implémente UserDetails pour permettre l'utilisation avec SwitchUserFilter.
 */
@Getter
public class CatapultOAuth2User implements OAuth2User, UserDetails {

    private static final long serialVersionUID = 1L;

    private final OAuth2User delegate; // null pour les sessions impersonifiées
    private final UserAccount userAccount;
    private final boolean admin;

    public CatapultOAuth2User(OAuth2User delegate, UserAccount userAccount, boolean admin) {
        this.delegate = delegate;
        this.userAccount = userAccount;
        this.admin = admin;
    }

    public static CatapultOAuth2User forImpersonation(UserAccount account, boolean admin) {
        return new CatapultOAuth2User(null, account, admin);
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate != null ? delegate.getAttributes() : Map.of();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        if (admin) {
            return List.of(
                new SimpleGrantedAuthority("ROLE_USER"),
                new SimpleGrantedAuthority("ROLE_ADMIN")
            );
        }
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getName() {
        return userAccount.getTwitchId();
    }

    // --- UserDetails ---

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    public String getUsername() {
        return userAccount.getTwitchUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return userAccount.getStatus() == UserAccount.Status.ACTIVE;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return userAccount.getStatus() == UserAccount.Status.ACTIVE;
    }
}
