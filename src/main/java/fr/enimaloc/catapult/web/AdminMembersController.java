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
        model.addAttribute("canMockSteam", Arrays.asList(environment.getActiveProfiles()).contains("mock-steam"));
        model.addAttribute("currentUserTwitchId", currentUser.getUserAccount().getTwitchId());
        return "admin/members";
    }
}
