package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.domain.account.UserAccount;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * OAuth2User wrapper that exposes the application's UserAccount.
 * ROLE_ADMIN is granted if the twitchId matches the configured owner.
 * Implements UserDetails so it can be used with SwitchUserFilter.
 */
@Getter
public class CatapultOAuth2User implements OAuth2User, UserDetails {

    @Serial
    private static final long serialVersionUID = 1L;

    private final OAuth2User delegate; // null for impersonated sessions
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
    @SuppressWarnings("NullableProblems")
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
    @SuppressWarnings("NullableProblems")
    public String getName() {
        return userAccount.getTwitchId() != null ? userAccount.getTwitchId() : userAccount.getId().toString();
    }

    // --- UserDetails ---

    @Override
    public String getPassword() {
        return null;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public String getUsername() {
        return userAccount.getTwitchUsername() != null ? userAccount.getTwitchUsername() : (userAccount.getTwitchId() != null ? userAccount.getTwitchId() : userAccount.getId().toString());
    }

    @Override
    public boolean isAccountNonLocked() {
        return userAccount.getStatus() == UserAccount.Status.ACTIVE;
    }

    @Override
    public boolean isEnabled() {
        return userAccount.getStatus() == UserAccount.Status.ACTIVE;
    }
}
