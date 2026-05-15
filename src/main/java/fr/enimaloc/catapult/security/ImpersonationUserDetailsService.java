package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ImpersonationUserDetailsService implements UserDetailsService {

    private final UserAccountRepository userAccountRepository;

    @Value("${app.owner-id:}")
    private String ownerId;

    @Override
    public UserDetails loadUserByUsername(String twitchUsername) throws UsernameNotFoundException {
        var account = userAccountRepository.findByTwitchUsername(twitchUsername)
            .orElseThrow(() -> new UsernameNotFoundException("User not found: " + twitchUsername));
        boolean isAdmin = !ownerId.isBlank() && ownerId.equals(account.getTwitchId());
        return CatapultOAuth2User.forImpersonation(account, isAdmin);
    }
}
