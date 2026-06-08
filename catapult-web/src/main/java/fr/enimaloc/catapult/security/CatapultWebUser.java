package fr.enimaloc.catapult.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Represents the authenticated user in catapult-web.
 * Built from JWT claims retrieved via catapult-api /api/auth/validate.
 */
public class CatapultWebUser implements UserDetails {

    private final UUID id;
    private final String twitchId;
    private final String username;
    private final String profileImageUrl;
    private final String status;
    private final List<String> roles;
    private final Instant deletionRequestedAt;

    public CatapultWebUser(UUID id, String twitchId, String username,
                           String profileImageUrl, String status, List<String> roles,
                           Instant deletionRequestedAt) {
        this.id = id;
        this.twitchId = twitchId;
        this.username = username;
        this.profileImageUrl = profileImageUrl;
        this.status = status;
        this.roles = roles != null ? roles : List.of();
        this.deletionRequestedAt = deletionRequestedAt;
    }

    public UUID getId() { return id; }
    public String getTwitchId() { return twitchId; }
    public String getProfileImageUrl() { return profileImageUrl; }
    public String getStatus() { return status; }
    public Instant getDeletionRequestedAt() { return deletionRequestedAt; }
    public boolean isAdmin() { return roles.contains("ROLE_ADMIN"); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(SimpleGrantedAuthority::new).toList();
    }

    @Override
    public String getPassword() { return null; }

    @Override
    public String getUsername() { return username; }

    @Override
    public boolean isEnabled() { return "ACTIVE".equals(status); }

    @Override
    public boolean isAccountNonLocked() { return isEnabled(); }
}
