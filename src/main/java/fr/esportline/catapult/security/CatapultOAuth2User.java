package fr.esportline.catapult.security;

import fr.esportline.catapult.domain.UserAccount;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Wrapper OAuth2User qui expose le UserAccount applicatif.
 */
@Getter
public class CatapultOAuth2User implements OAuth2User {

    private final OAuth2User delegate;
    private final UserAccount userAccount;

    public CatapultOAuth2User(OAuth2User delegate, UserAccount userAccount) {
        this.delegate = delegate;
        this.userAccount = userAccount;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return delegate.getAttributes();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_USER"));
    }

    @Override
    public String getName() {
        return userAccount.getTwitchId();
    }
}
