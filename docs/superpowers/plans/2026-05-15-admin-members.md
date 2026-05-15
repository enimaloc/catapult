# Admin Members Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `/admin/members` page listing all `UserAccount` members with per-user mock controls (Twitch stream state, Steam game) and account impersonation via Spring Security `SwitchUserFilter`.

**Architecture:** A profile-agnostic `AdminMembersController` handles the GET page; a `@Profile("mock")` `AdminMembersMockController` handles POST mock actions. Impersonation uses Spring Security's built-in `SwitchUserFilter` wired to a new `ImpersonationUserDetailsService`. Existing `MockTwitchController`, `MockSteamController`, and `MockSecurityConfig` are deleted.

**Tech Stack:** Spring Boot 4.x (Gradle), Spring Security 6.x, Thymeleaf, JPA/Hibernate, JUnit 5 + AssertJ + Mockito

---

## File Map

| Action | Path |
|--------|------|
| Modify | `src/main/java/fr/enimaloc/catapult/repository/UserAccountRepository.java` |
| Modify | `src/main/java/fr/enimaloc/catapult/security/CatapultOAuth2User.java` |
| Modify | `src/test/java/fr/enimaloc/catapult/security/CatapultOAuth2UserTest.java` |
| Create | `src/main/java/fr/enimaloc/catapult/security/ImpersonationUserDetailsService.java` |
| Create | `src/test/java/fr/enimaloc/catapult/security/ImpersonationUserDetailsServiceTest.java` |
| Modify | `src/main/java/fr/enimaloc/catapult/security/SecurityConfig.java` |
| Create | `src/main/java/fr/enimaloc/catapult/web/AdminMembersController.java` |
| Create | `src/test/java/fr/enimaloc/catapult/web/AdminMembersControllerTest.java` |
| Create | `src/main/java/fr/enimaloc/catapult/web/AdminMembersMockController.java` |
| Create | `src/test/java/fr/enimaloc/catapult/web/AdminMembersMockControllerTest.java` |
| Create | `src/main/resources/templates/admin/members.html` |
| Modify | `src/main/resources/templates/fragments/nav.html` |
| Delete | `src/main/java/fr/enimaloc/catapult/web/MockTwitchController.java` |
| Delete | `src/main/java/fr/enimaloc/catapult/web/MockSteamController.java` |
| Delete | `src/main/java/fr/enimaloc/catapult/security/MockSecurityConfig.java` |
| Delete | `src/main/resources/templates/mock/twitch.html` |
| Delete | `src/main/resources/templates/mock/steam.html` |

---

## Task 1: Add `findByTwitchUsername` to `UserAccountRepository`

`ImpersonationUserDetailsService` needs to look up users by `twitchUsername`. Spring Data derives the query from the method name — no implementation required.

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/repository/UserAccountRepository.java`

- [ ] **Step 1: Add the derived query method**

In `UserAccountRepository.java`, add one line after `findByTwitchId`:

```java
Optional<UserAccount> findByTwitchUsername(String twitchUsername);
```

The full interface after the change:

```java
package fr.enimaloc.catapult.repository;

import fr.enimaloc.catapult.domain.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    Optional<UserAccount> findByTwitchId(String twitchId);

    Optional<UserAccount> findByTwitchUsername(String twitchUsername);

    Optional<UserAccount> findByApiKey(String apiKey);

    List<UserAccount> findByBotEnabledTrueAndStatus(UserAccount.Status status);

    List<UserAccount> findBySteamIdNotNull();

    List<UserAccount> findByStatusAndDeletionRequestedAtBefore(UserAccount.Status status, Instant cutoff);
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/repository/UserAccountRepository.java
git commit -m "feat: add findByTwitchUsername to UserAccountRepository"
```

---

## Task 2: Extend `CatapultOAuth2User` to implement `UserDetails`

`SwitchUserFilter` calls `userDetailsService.loadUserByUsername()` and expects a `UserDetails` back. Making `CatapultOAuth2User` implement `UserDetails` means the impersonated session has the same principal type as a normal OAuth2 session — controllers using `@AuthenticationPrincipal CatapultOAuth2User` continue to work after impersonation.

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/security/CatapultOAuth2User.java`
- Modify: `src/test/java/fr/enimaloc/catapult/security/CatapultOAuth2UserTest.java`

- [ ] **Step 1: Write the failing tests**

Replace the full content of `CatapultOAuth2UserTest.java`:

```java
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
```

- [ ] **Step 2: Run the tests — they should fail**

```bash
./gradlew test --tests "fr.enimaloc.catapult.security.CatapultOAuth2UserTest"
```

Expected: FAIL — `getUsername()`, `isEnabled()`, `isAccountNonLocked()`, `forImpersonation` not found.

- [ ] **Step 3: Update `CatapultOAuth2User` to implement `UserDetails`**

Replace the full file content:

```java
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
```

- [ ] **Step 4: Run the tests — they should pass**

```bash
./gradlew test --tests "fr.enimaloc.catapult.security.CatapultOAuth2UserTest"
```

Expected: all 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/security/CatapultOAuth2User.java \
        src/test/java/fr/enimaloc/catapult/security/CatapultOAuth2UserTest.java
git commit -m "feat: implement UserDetails on CatapultOAuth2User for SwitchUserFilter support"
```

---

## Task 3: Create `ImpersonationUserDetailsService`

This service is called by `SwitchUserFilter` when the admin clicks "Impersonate". It resolves a `UserAccount` by `twitchUsername` and returns a `CatapultOAuth2User` — the same principal type the rest of the app expects.

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/security/ImpersonationUserDetailsService.java`
- Create: `src/test/java/fr/enimaloc/catapult/security/ImpersonationUserDetailsServiceTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/fr/enimaloc/catapult/security/ImpersonationUserDetailsServiceTest.java`:

```java
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
```

- [ ] **Step 2: Run the tests — they should fail**

```bash
./gradlew test --tests "fr.enimaloc.catapult.security.ImpersonationUserDetailsServiceTest"
```

Expected: FAIL — class not found.

- [ ] **Step 3: Create `ImpersonationUserDetailsService`**

Create `src/main/java/fr/enimaloc/catapult/security/ImpersonationUserDetailsService.java`:

```java
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
```

- [ ] **Step 4: Run the tests — they should pass**

```bash
./gradlew test --tests "fr.enimaloc.catapult.security.ImpersonationUserDetailsServiceTest"
```

Expected: all 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/security/ImpersonationUserDetailsService.java \
        src/test/java/fr/enimaloc/catapult/security/ImpersonationUserDetailsServiceTest.java
git commit -m "feat: add ImpersonationUserDetailsService for SwitchUserFilter"
```

---

## Task 4: Configure `SwitchUserFilter` in `SecurityConfig`

Wire the filter into the security chain. Two important rules:
- `/admin/impersonate` (switch entry) — protected by `hasRole("ADMIN")`, which the existing `/admin/**` rule already covers
- `/admin/impersonate/exit` (switch exit) — must be accessible to impersonated users who only have `ROLE_USER` + `ROLE_PREVIOUS_ADMINISTRATOR`. It must be listed **before** the `/admin/**` rule so it is matched first.

**Files:**
- Modify: `src/main/java/fr/enimaloc/catapult/security/SecurityConfig.java`

- [ ] **Step 1: Replace `SecurityConfig.java` with the updated version**

```java
package fr.enimaloc.catapult.security;

import fr.enimaloc.catapult.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.switchuser.SwitchUserFilter;

@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CatapultOAuth2UserService oAuth2UserService;

    @Bean
    public ApiKeyAuthFilter apiKeyAuthFilter(UserAccountRepository userAccountRepository) {
        return new ApiKeyAuthFilter(userAccountRepository);
    }

    @Bean
    public SwitchUserFilter switchUserFilter(ImpersonationUserDetailsService impersonationUserDetailsService) {
        SwitchUserFilter filter = new SwitchUserFilter();
        filter.setUserDetailsService(impersonationUserDetailsService);
        filter.setSwitchUserUrl("/admin/impersonate");
        filter.setExitUserUrl("/admin/impersonate/exit");
        filter.setSuccessHandler((req, res, auth) -> res.sendRedirect("/app"));
        filter.setExitSuccessHandler((req, res, auth) -> res.sendRedirect("/admin/members"));
        filter.setFailureHandler((req, res, ex) -> {
            log.warn("Impersonation failed: {}", ex.getMessage());
            res.sendRedirect("/admin/members?error=impersonateFailed");
        });
        filter.setUserDetailsChecker(details -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof CatapultOAuth2User admin
                && admin.getUserAccount().getTwitchUsername().equals(details.getUsername())) {
                throw new LockedException("Cannot impersonate yourself");
            }
        });
        return filter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                    ApiKeyAuthFilter apiKeyAuthFilter,
                                                    SwitchUserFilter switchUserFilter) throws Exception {
        http
            .addFilterBefore(apiKeyAuthFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(switchUserFilter, org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
            .csrf(csrf -> csrf.ignoringRequestMatchers("/api/obs/**"))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/error", "/css/**", "/js/**", "/images/**").permitAll()
                .requestMatchers("/api/obs/**").authenticated()
                .requestMatchers("/admin/impersonate/exit").authenticated()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth2 -> oauth2
                .loginPage("/login")
                .defaultSuccessUrl("/app", true)
                .userInfoEndpoint(userInfo -> userInfo.userService(oAuth2UserService))
                .failureHandler((request, response, exception) -> {
                    log.error("OAuth2 login failed: [{}] {}", exception.getClass().getSimpleName(), exception.getMessage(), exception);
                    new SimpleUrlAuthenticationFailureHandler("/login?error").onAuthenticationFailure(request, response, exception);
                })
            )
            .logout(logout -> logout
                .logoutSuccessUrl("/login")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
            );

        return http.build();
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/security/SecurityConfig.java
git commit -m "feat: configure SwitchUserFilter for admin account impersonation"
```

---

## Task 5: Create `AdminMembersController`

The GET handler for `/admin/members`. No `@Profile` annotation — it works in prod and mock. It loads all members, their live status, and passes a `isMockProfile` flag so the template knows whether to show mock action columns.

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/web/AdminMembersController.java`
- Create: `src/test/java/fr/enimaloc/catapult/web/AdminMembersControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/fr/enimaloc/catapult/web/AdminMembersControllerTest.java`:

```java
package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.service.StreamStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.env.Environment;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMembersControllerTest {

    @Mock private UserAccountRepository userAccountRepository;
    @Mock private StreamStateService streamStateService;
    @Mock private Environment environment;
    @InjectMocks private AdminMembersController controller;

    private CatapultOAuth2User adminPrincipal(String twitchId) {
        UserAccount account = new UserAccount();
        account.setId(UUID.randomUUID());
        account.setTwitchId(twitchId);
        account.setTwitchUsername("admin");
        account.setStatus(UserAccount.Status.ACTIVE);
        return CatapultOAuth2User.forImpersonation(account, true);
    }

    @Test
    void page_populatesMembersAndLiveStatus() {
        UserAccount user = new UserAccount();
        user.setId(UUID.randomUUID());
        user.setTwitchId("user1");
        user.setTwitchUsername("streamer");
        user.setStatus(UserAccount.Status.ACTIVE);

        when(userAccountRepository.findAll()).thenReturn(List.of(user));
        when(streamStateService.isLive(user)).thenReturn(true);
        when(environment.getActiveProfiles()).thenReturn(new String[]{});

        Model model = new ExtendedModelMap();
        String view = controller.page(model, adminPrincipal("adminId"));

        assertThat(view).isEqualTo("admin/members");
        assertThat(model.getAttribute("members")).isEqualTo(List.of(user));
        @SuppressWarnings("unchecked")
        Map<UUID, Boolean> liveStatus = (Map<UUID, Boolean>) model.getAttribute("liveStatus");
        assertThat(liveStatus).containsEntry(user.getId(), true);
    }

    @Test
    void page_noMockProfile_isMockProfileFalse() {
        when(userAccountRepository.findAll()).thenReturn(List.of());
        when(environment.getActiveProfiles()).thenReturn(new String[]{});

        Model model = new ExtendedModelMap();
        controller.page(model, adminPrincipal("adminId"));

        assertThat(model.getAttribute("isMockProfile")).isEqualTo(false);
    }

    @Test
    void page_mockProfile_isMockProfileTrue() {
        when(userAccountRepository.findAll()).thenReturn(List.of());
        when(environment.getActiveProfiles()).thenReturn(new String[]{"mock"});

        Model model = new ExtendedModelMap();
        controller.page(model, adminPrincipal("adminId"));

        assertThat(model.getAttribute("isMockProfile")).isEqualTo(true);
    }

    @Test
    void page_currentUserTwitchIdInModel() {
        when(userAccountRepository.findAll()).thenReturn(List.of());
        when(environment.getActiveProfiles()).thenReturn(new String[]{});

        Model model = new ExtendedModelMap();
        controller.page(model, adminPrincipal("myTwitchId"));

        assertThat(model.getAttribute("currentUserTwitchId")).isEqualTo("myTwitchId");
    }
}
```

- [ ] **Step 2: Run the tests — they should fail**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.AdminMembersControllerTest"
```

Expected: FAIL — class not found.

- [ ] **Step 3: Create `AdminMembersController`**

Create `src/main/java/fr/enimaloc/catapult/web/AdminMembersController.java`:

```java
package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.service.StreamStateService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/members")
@RequiredArgsConstructor
public class AdminMembersController {

    private final UserAccountRepository userAccountRepository;
    private final StreamStateService streamStateService;
    private final Environment environment;

    @GetMapping
    public String page(Model model, @AuthenticationPrincipal CatapultOAuth2User currentUser) {
        List<UserAccount> members = userAccountRepository.findAll();
        Map<UUID, Boolean> liveStatus = members.stream()
            .collect(Collectors.toMap(UserAccount::getId, streamStateService::isLive));

        model.addAttribute("members", members);
        model.addAttribute("liveStatus", liveStatus);
        model.addAttribute("isMockProfile", Arrays.asList(environment.getActiveProfiles()).contains("mock"));
        model.addAttribute("currentUserTwitchId", currentUser.getUserAccount().getTwitchId());
        return "admin/members";
    }
}
```

- [ ] **Step 4: Run the tests — they should pass**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.AdminMembersControllerTest"
```

Expected: all 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/web/AdminMembersController.java \
        src/test/java/fr/enimaloc/catapult/web/AdminMembersControllerTest.java
git commit -m "feat: add AdminMembersController for GET /admin/members"
```

---

## Task 6: Create `AdminMembersMockController`

POST endpoints for mocking Twitch and Steam state per member. Only active with `@Profile("mock")`. Resolves members by UUID path variable; returns 404 if not found.

**Files:**
- Create: `src/main/java/fr/enimaloc/catapult/web/AdminMembersMockController.java`
- Create: `src/test/java/fr/enimaloc/catapult/web/AdminMembersMockControllerTest.java`

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/fr/enimaloc/catapult/web/AdminMembersMockControllerTest.java`:

```java
package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.MockSteamApiClient;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.MockTwitchEventSubService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminMembersMockControllerTest {

    @Mock private UserAccountRepository userAccountRepository;
    @Mock private MockTwitchEventSubService mockTwitchEventSubService;
    @Mock private MockSteamApiClient mockSteamApiClient;
    @InjectMocks private AdminMembersMockController controller;

    private UserAccount userWithSteam(UUID id) {
        UserAccount user = new UserAccount();
        user.setId(id);
        user.setSteamId("steam123");
        return user;
    }

    @Test
    void setTwitchOnline_delegatesToService_andRedirects() {
        UUID id = UUID.randomUUID();
        UserAccount user = new UserAccount(); user.setId(id);
        when(userAccountRepository.findById(id)).thenReturn(Optional.of(user));

        String result = controller.setTwitchOnline(id);

        verify(mockTwitchEventSubService).setOnline(user);
        assertThat(result).isEqualTo("redirect:/admin/members");
    }

    @Test
    void setTwitchOnline_userNotFound_throws404() {
        UUID id = UUID.randomUUID();
        when(userAccountRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.setTwitchOnline(id))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void setTwitchOffline_delegatesToService_andRedirects() {
        UUID id = UUID.randomUUID();
        UserAccount user = new UserAccount(); user.setId(id);
        when(userAccountRepository.findById(id)).thenReturn(Optional.of(user));

        String result = controller.setTwitchOffline(id);

        verify(mockTwitchEventSubService).setOffline(user);
        assertThat(result).isEqualTo("redirect:/admin/members");
    }

    @Test
    void setSteamGame_delegatesToClient_andRedirects() {
        UUID id = UUID.randomUUID();
        UserAccount user = userWithSteam(id);
        when(userAccountRepository.findById(id)).thenReturn(Optional.of(user));

        String result = controller.setSteamGame(id, "570", "Dota 2");

        verify(mockSteamApiClient).setGameForUser("steam123", "570", "Dota 2");
        assertThat(result).isEqualTo("redirect:/admin/members");
    }

    @Test
    void setSteamGame_userHasNoSteamId_throws400() {
        UUID id = UUID.randomUUID();
        UserAccount user = new UserAccount(); user.setId(id);
        when(userAccountRepository.findById(id)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> controller.setSteamGame(id, "570", "Dota 2"))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void clearSteamGame_delegatesToClient_andRedirects() {
        UUID id = UUID.randomUUID();
        UserAccount user = userWithSteam(id);
        when(userAccountRepository.findById(id)).thenReturn(Optional.of(user));

        String result = controller.clearSteamGame(id);

        verify(mockSteamApiClient).clearGameForUser("steam123");
        assertThat(result).isEqualTo("redirect:/admin/members");
    }
}
```

- [ ] **Step 2: Run the tests — they should fail**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.AdminMembersMockControllerTest"
```

Expected: FAIL — class not found.

- [ ] **Step 3: Create `AdminMembersMockController`**

Create `src/main/java/fr/enimaloc/catapult/web/AdminMembersMockController.java`:

```java
package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.getter.MockSteamApiClient;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.MockTwitchEventSubService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Controller
@Profile("mock")
@RequestMapping("/admin/members")
@RequiredArgsConstructor
public class AdminMembersMockController {

    private final UserAccountRepository userAccountRepository;
    private final MockTwitchEventSubService mockTwitchEventSubService;
    private final MockSteamApiClient mockSteamApiClient;

    @PostMapping("/{id}/twitch/online")
    public String setTwitchOnline(@PathVariable UUID id) {
        var user = userAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        mockTwitchEventSubService.setOnline(user);
        return "redirect:/admin/members";
    }

    @PostMapping("/{id}/twitch/offline")
    public String setTwitchOffline(@PathVariable UUID id) {
        var user = userAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        mockTwitchEventSubService.setOffline(user);
        return "redirect:/admin/members";
    }

    @PostMapping("/{id}/steam/set")
    public String setSteamGame(@PathVariable UUID id,
                                @RequestParam String gameId,
                                @RequestParam String gameName) {
        var user = userAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.getSteamId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User has no Steam ID");
        }
        mockSteamApiClient.setGameForUser(user.getSteamId(), gameId.strip(), gameName.strip());
        return "redirect:/admin/members";
    }

    @PostMapping("/{id}/steam/clear")
    public String clearSteamGame(@PathVariable UUID id) {
        var user = userAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.getSteamId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "User has no Steam ID");
        }
        mockSteamApiClient.clearGameForUser(user.getSteamId());
        return "redirect:/admin/members";
    }
}
```

- [ ] **Step 4: Run the tests — they should pass**

```bash
./gradlew test --tests "fr.enimaloc.catapult.web.AdminMembersMockControllerTest"
```

Expected: all 6 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/fr/enimaloc/catapult/web/AdminMembersMockController.java \
        src/test/java/fr/enimaloc/catapult/web/AdminMembersMockControllerTest.java
git commit -m "feat: add AdminMembersMockController for mock Twitch/Steam actions"
```

---

## Task 7: Create `templates/admin/members.html`

The Thymeleaf page. Follows the same structure as `admin/ccl.html`. Mock action columns are conditionally rendered via `th:if="${isMockProfile}"`. The impersonate button is disabled for the current admin's own row.

**Files:**
- Create: `src/main/resources/templates/admin/members.html`

- [ ] **Step 1: Create the template**

Create `src/main/resources/templates/admin/members.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="">
<head th:replace="~{fragments/head :: head('Catapult — Membres')}"></head>
<body>
    <div th:replace="~{fragments/nav :: nav('members')}"></div>

    <main class="container">
        <h2>Administration — Membres</h2>

        <div th:if="${#lists.isEmpty(members)}" class="text-muted">
            <p>Aucun membre en base.</p>
        </div>

        <div th:unless="${#lists.isEmpty(members)}" class="table-container">
            <table class="table">
                <thead>
                    <tr>
                        <th>Utilisateur Twitch</th>
                        <th>Steam ID</th>
                        <th>Bot</th>
                        <th>Statut</th>
                        <th>Créé le</th>
                        <th th:if="${isMockProfile}">Mock Twitch</th>
                        <th th:if="${isMockProfile}">Mock Steam</th>
                        <th>Actions</th>
                    </tr>
                </thead>
                <tbody>
                    <tr th:each="member : ${members}">
                        <td>
                            <strong th:text="${member.twitchUsername}"></strong><br>
                            <span class="badge" th:text="${member.twitchId}"></span>
                        </td>
                        <td class="text-muted" th:text="${member.steamId != null ? member.steamId : '—'}"></td>
                        <td>
                            <span th:if="${member.botEnabled}" class="badge badge-live">✓</span>
                            <span th:unless="${member.botEnabled}" class="badge">✗</span>
                        </td>
                        <td>
                            <span th:text="${member.status}"
                                  th:class="${member.status.name() == 'ACTIVE'} ? 'badge badge-live' : 'badge'"></span>
                        </td>
                        <td class="text-muted text-sm" th:text="${member.createdAt}"></td>

                        <!-- Mock Twitch -->
                        <td th:if="${isMockProfile}">
                            <div style="display:flex;gap:6px">
                                <form th:action="@{/admin/members/{id}/twitch/online(id=${member.id})}" method="post"> <!-- nosemgrep -->
                                    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                                    <button type="submit" class="btn btn-danger btn-sm"
                                            th:disabled="${liveStatus[member.id]}">▶ Online</button>
                                </form>
                                <form th:action="@{/admin/members/{id}/twitch/offline(id=${member.id})}" method="post"> <!-- nosemgrep -->
                                    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                                    <button type="submit" class="btn btn-sm"
                                            th:disabled="${!liveStatus[member.id]}">■ Offline</button>
                                </form>
                            </div>
                        </td>

                        <!-- Mock Steam -->
                        <td th:if="${isMockProfile}">
                            <div th:if="${member.steamId != null}" style="display:flex;gap:6px;align-items:center">
                                <form th:action="@{/admin/members/{id}/steam/set(id=${member.id})}" method="post"
                                      style="display:flex;gap:4px"> <!-- nosemgrep -->
                                    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                                    <input type="text" name="gameId" placeholder="Game ID"
                                           style="width:80px" class="form-control form-control-sm"/>
                                    <input type="text" name="gameName" placeholder="Nom du jeu"
                                           style="width:130px" class="form-control form-control-sm"/>
                                    <button type="submit" class="btn btn-sm btn-primary">Set</button>
                                </form>
                                <form th:action="@{/admin/members/{id}/steam/clear(id=${member.id})}" method="post"> <!-- nosemgrep -->
                                    <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                                    <button type="submit" class="btn btn-sm">Clear</button>
                                </form>
                            </div>
                            <span th:if="${member.steamId == null}" class="text-muted">—</span>
                        </td>

                        <!-- Impersonate -->
                        <td>
                            <form th:action="@{/admin/impersonate}" method="post"> <!-- nosemgrep -->
                                <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                                <input type="hidden" name="username" th:value="${member.twitchUsername}"/>
                                <button type="submit" class="btn btn-sm"
                                        th:disabled="${member.twitchId == currentUserTwitchId}">Impersonate</button>
                            </form>
                        </td>
                    </tr>
                </tbody>
            </table>
        </div>
    </main>
</body>
</html>
```

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/admin/members.html
git commit -m "feat: add admin/members.html template"
```

---

## Task 8: Update `nav.html`

Two changes:
1. Add an impersonation banner that appears when the current session has `ROLE_PREVIOUS_ADMINISTRATOR` (i.e., is currently impersonating someone).
2. Replace the two separate `/mock/twitch` and `/mock/steam` nav links with a single `/admin/members` link.

**Files:**
- Modify: `src/main/resources/templates/fragments/nav.html`

- [ ] **Step 1: Replace `nav.html` with the updated version**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="">
<body>

<div sec:authorize="hasAuthority('ROLE_PREVIOUS_ADMINISTRATOR')" class="impersonation-banner"
     style="background:var(--color-warning,#f59e0b);color:#000;padding:0.5rem 1rem;display:flex;align-items:center;gap:1rem;font-size:0.9rem;">
    <span>Connecté en tant que <strong sec:authentication="principal.username"></strong></span>
    <form th:action="@{/admin/impersonate/exit}" method="post" style="display:inline"> <!-- nosemgrep -->
        <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
        <button type="submit" class="btn btn-sm">Retour à l'admin</button>
    </form>
</div>

<nav th:fragment="nav(active)" class="navbar">
    <a th:href="@{/app}" class="nav-brand">🚀 Catapult</a>
    <div class="nav-links">
        <a th:href="@{/app}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'app'} ? ' active'">App</a>
        <a th:href="@{/admin/ccl}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'ccl'} ? ' active'">Admin CCL</a>
        <a th:href="@{/admin/process-rules}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'process-rules'} ? ' active'">Règles processus</a>
        <a th:href="@{/admin/members}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'members'} ? ' active'">Membres</a>
        <a th:href="@{/dev/igdb}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'igdb'} ? ' active'" th:if="${#arrays.contains(@environment.getActiveProfiles(), 'dev')}">IGDB Explorer</a>
        <form th:action="@{/logout}" method="post" style="display:inline">
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
            <button type="submit" class="btn-link">Déconnexion</button>
        </form>
    </div>
    <div class="theme-switcher">
        <button class="theme-btn" data-theme="dark"        onclick="setTheme('dark')"        title="Dark">🌙</button>
        <button class="theme-btn" data-theme="blanc"       onclick="setTheme('blanc')"       title="Blanc">☀️</button>
        <button class="theme-btn" data-theme="old-steam"   onclick="setTheme('old-steam')"   title="Old Steam">🎮</button>
        <button class="theme-btn" data-theme="nord"        onclick="setTheme('nord')"        title="Nord">❄️</button>
        <button class="theme-btn" data-theme="dracula"     onclick="setTheme('dracula')"     title="Dracula">🧛</button>
        <button class="theme-btn" data-theme="catppuccin"  onclick="setTheme('catppuccin')"  title="Catppuccin">🐱</button>
        <button class="theme-btn" data-theme="tokyo-night" onclick="setTheme('tokyo-night')" title="Tokyo Night">🌃</button>
    </div>
</nav>
</body>
</html>
```

**Note:** The impersonation banner is placed **outside** the `th:fragment` block intentionally. Thymeleaf fragment replacement (`th:replace`) only inserts the fragment content, not the banner. The banner must be inserted by all templates that include the nav. Since all existing templates use `<div th:replace="~{fragments/nav :: nav('...')}">`, change the include pattern in templates that should show the banner.

Actually, the cleanest approach: place the banner **inside** the `th:fragment` so it is always included with the nav. Replace the outer `<div th:replace=...>` with the fragment reference in each template if needed, or use a wrapper fragment.

**Revised approach** — put banner inside the fragment:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      xmlns:sec="">
<body>
<th:block th:fragment="nav(active)">
    <div sec:authorize="hasAuthority('ROLE_PREVIOUS_ADMINISTRATOR')"
         style="background:var(--color-warning,#f59e0b);color:#000;padding:0.5rem 1rem;display:flex;align-items:center;gap:1rem;font-size:0.9rem;">
        <span>Connecté en tant que <strong sec:authentication="principal.username"></strong></span>
        <form th:action="@{/admin/impersonate/exit}" method="post" style="display:inline"> <!-- nosemgrep -->
            <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
            <button type="submit" class="btn btn-sm">Retour à l'admin</button>
        </form>
    </div>
    <nav class="navbar">
        <a th:href="@{/app}" class="nav-brand">🚀 Catapult</a>
        <div class="nav-links">
            <a th:href="@{/app}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'app'} ? ' active'">App</a>
            <a th:href="@{/admin/ccl}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'ccl'} ? ' active'">Admin CCL</a>
            <a th:href="@{/admin/process-rules}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'process-rules'} ? ' active'">Règles processus</a>
            <a th:href="@{/admin/members}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'members'} ? ' active'">Membres</a>
            <a th:href="@{/dev/igdb}" sec:authorize="hasRole('ADMIN')" th:classappend="${active == 'igdb'} ? ' active'" th:if="${#arrays.contains(@environment.getActiveProfiles(), 'dev')}">IGDB Explorer</a>
            <form th:action="@{/logout}" method="post" style="display:inline">
                <input type="hidden" th:name="${_csrf.parameterName}" th:value="${_csrf.token}"/>
                <button type="submit" class="btn-link">Déconnexion</button>
            </form>
        </div>
        <div class="theme-switcher">
            <button class="theme-btn" data-theme="dark"        onclick="setTheme('dark')"        title="Dark">🌙</button>
            <button class="theme-btn" data-theme="blanc"       onclick="setTheme('blanc')"       title="Blanc">☀️</button>
            <button class="theme-btn" data-theme="old-steam"   onclick="setTheme('old-steam')"   title="Old Steam">🎮</button>
            <button class="theme-btn" data-theme="nord"        onclick="setTheme('nord')"        title="Nord">❄️</button>
            <button class="theme-btn" data-theme="dracula"     onclick="setTheme('dracula')"     title="Dracula">🧛</button>
            <button class="theme-btn" data-theme="catppuccin"  onclick="setTheme('catppuccin')"  title="Catppuccin">🐱</button>
            <button class="theme-btn" data-theme="tokyo-night" onclick="setTheme('tokyo-night')" title="Tokyo Night">🌃</button>
        </div>
    </nav>
</th:block>
</body>
</html>
```

Using `<th:block th:fragment="nav(active)">` lets the fragment include both the banner div and the `<nav>` without needing a wrapper element. All existing templates use `<div th:replace="~{fragments/nav :: nav('...')}">` — the `div` becomes the wrapper, so no changes needed in consuming templates.

- [ ] **Step 2: Verify compilation**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/fragments/nav.html
git commit -m "feat: add impersonation banner and members nav link"
```

---

## Task 9: Delete old mock controllers, security config, and templates

`MockTwitchController` and `MockSteamController` are fully replaced by `AdminMembersMockController`. `MockSecurityConfig` exists only to permit `/mock/**` — with no controllers serving that path, it is dead code.

**Files:**
- Delete: `src/main/java/fr/enimaloc/catapult/web/MockTwitchController.java`
- Delete: `src/main/java/fr/enimaloc/catapult/web/MockSteamController.java`
- Delete: `src/main/java/fr/enimaloc/catapult/security/MockSecurityConfig.java`
- Delete: `src/main/resources/templates/mock/twitch.html`
- Delete: `src/main/resources/templates/mock/steam.html`

- [ ] **Step 1: Delete the files**

```bash
rm src/main/java/fr/enimaloc/catapult/web/MockTwitchController.java
rm src/main/java/fr/enimaloc/catapult/web/MockSteamController.java
rm src/main/java/fr/enimaloc/catapult/security/MockSecurityConfig.java
rm src/main/resources/templates/mock/twitch.html
rm src/main/resources/templates/mock/steam.html
```

- [ ] **Step 2: Run the full test suite**

```bash
./gradlew test
```

Expected: `BUILD SUCCESSFUL`, all tests pass. If any test imports deleted classes, update the imports.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "chore: remove MockTwitchController, MockSteamController and MockSecurityConfig"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Task |
|-----------------|------|
| `/admin/members` page listing all UserAccounts | Task 5, 7 |
| Mock Twitch state (online/offline) per user | Task 6, 7 |
| Mock Steam game per user | Task 6, 7 |
| Mock actions conditional on `mock` profile | Task 5, 7 |
| Impersonation via SwitchUserFilter | Task 3, 4 |
| Impersonation banner in nav | Task 8 |
| Self-impersonation guard | Task 4 (UserDetailsChecker) |
| `/admin/impersonate/exit` accessible to impersonated users | Task 4 |
| Delete old mock controllers | Task 9 |
| `ImpersonationUserDetailsService` | Task 3 |

All spec requirements are covered.

**Placeholder scan:** No TBD, TODO, or vague steps found.

**Type consistency:**
- `CatapultOAuth2User.forImpersonation(UserAccount, boolean)` defined Task 2, used Task 3 ✓
- `userAccountRepository.findByTwitchUsername(String)` defined Task 1, used Task 3 ✓
- `AdminMembersController.page(Model, CatapultOAuth2User)` defined Task 5, rendered by Task 7 model keys: `members`, `liveStatus`, `isMockProfile`, `currentUserTwitchId` ✓
- `AdminMembersMockController` path variables use `UUID id`, repository uses `findById(id)` ✓
- Nav fragment changed from `<nav th:fragment="nav(active)">` to `<th:block th:fragment="nav(active)">` — all consuming templates use `th:replace="~{fragments/nav :: nav('...')}"` which works with both element types ✓
