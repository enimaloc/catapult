package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.OAuthToken;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.getter.MockSteamApiClient;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import fr.enimaloc.catapult.service.AccountService;
import fr.enimaloc.catapult.service.AdminMigrationService;
import fr.enimaloc.catapult.service.StreamStateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequestMapping("/admin/members")
@RequiredArgsConstructor
public class AdminMembersController {

    private final UserAccountRepository userAccountRepository;
    private final StreamStateService streamStateService;
    private final Environment environment;
    private final Optional<MockSteamApiClient> mockSteamApiClient;
    private final AccountService accountService;
    private final AdminMigrationService adminMigrationService;

    @PostMapping("/{id}/bot/toggle")
    public String toggleBot(@PathVariable UUID id) {
        UserAccount user = userAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        user.setBotEnabled(!user.isBotEnabled());
        userAccountRepository.save(user);
        return "redirect:/admin/members";
    }

    @PostMapping("/{id}/delete")
    public String deleteAccount(@PathVariable UUID id,
                                @AuthenticationPrincipal CatapultOAuth2User principal) {
        UserAccount user = userAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (Objects.equals(user.getTwitchId(), principal.getUserAccount().getTwitchId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        log.info("Admin {} deleted account {} (twitchId={})",
            principal.getUserAccount().getTwitchId(), user.getId(), user.getTwitchId());
        accountService.deleteAccountImmediately(user);
        return "redirect:/admin/members";
    }

    @PostMapping("/{id}/steam/unlink")
    public String unlinkSteam(@PathVariable UUID id) {
        UserAccount user = userAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.getSteamId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        log.info("Admin unlinked Steam for account {} (twitchId={})", user.getId(), user.getTwitchId());
        accountService.disconnectProvider(user, OAuthToken.Provider.STEAM);
        return "redirect:/admin/members";
    }

    @PostMapping("/{id}/twitch/unlink")
    public String unlinkTwitch(@PathVariable UUID id) {
        UserAccount user = userAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (user.getStatus() != UserAccount.Status.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        log.info("Admin unlinked Twitch for account {} (twitchId={})", user.getId(), user.getTwitchId());
        accountService.unlinkTwitch(user);
        return "redirect:/admin/members";
    }

    @PostMapping("/{id}/migrate")
    public String migrateData(@PathVariable UUID id,
                              @RequestParam UUID targetId,
                              @RequestParam(defaultValue = "false") boolean migrateSettings,
                              @RequestParam(defaultValue = "false") boolean migrateGetters,
                              @RequestParam(defaultValue = "false") boolean migrateBindings) {
        UserAccount source = userAccountRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        UserAccount target = userAccountRepository.findById(targetId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (source.getId().equals(target.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (!migrateSettings && !migrateGetters && !migrateBindings) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        log.info("Admin migrated data from {} to {} (settings={} getters={} bindings={})",
            source.getId(), target.getId(), migrateSettings, migrateGetters, migrateBindings);
        adminMigrationService.migrate(source, target,
            new AdminMigrationService.MigrateOptions(migrateSettings, migrateGetters, migrateBindings));
        return "redirect:/admin/members";
    }

    @GetMapping
    public String page(Model model, @AuthenticationPrincipal CatapultOAuth2User principal) {
        List<UserAccount> members = userAccountRepository.findAll();
        Map<UUID, Boolean> liveStatus = members.stream()
            .collect(Collectors.toMap(UserAccount::getId, streamStateService::isLive));

        Set<String> privateSteamProfiles = mockSteamApiClient
            .map(MockSteamApiClient::getPrivateProfiles)
            .orElse(Set.of());
        boolean steamRateLimited = mockSteamApiClient
            .map(MockSteamApiClient::isRateLimited)
            .orElse(false);

        model.addAttribute("members", members);
        model.addAttribute("liveStatus", liveStatus);
        model.addAttribute("isMockProfile", Arrays.asList(environment.getActiveProfiles()).contains("mock"));
        model.addAttribute("canMockSteam", Arrays.asList(environment.getActiveProfiles()).contains("mock-steam"));
        model.addAttribute("privateSteamProfiles", privateSteamProfiles);
        model.addAttribute("steamRateLimited", steamRateLimited);
        model.addAttribute("currentUserTwitchId", principal.getUserAccount().getTwitchId());
        return "admin/members";
    }
}
