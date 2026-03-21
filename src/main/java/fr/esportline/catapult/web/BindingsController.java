package fr.esportline.catapult.web;

import fr.esportline.catapult.domain.GameBinding;
import fr.esportline.catapult.domain.TwitchCcl;
import fr.esportline.catapult.domain.UserAccount;
import fr.esportline.catapult.repository.GameBindingRepository;
import fr.esportline.catapult.security.CatapultOAuth2User;
import fr.esportline.catapult.service.BindingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class BindingsController {

    private final GameBindingRepository gameBindingRepository;
    private final BindingService bindingService;

    @GetMapping("/bindings")
    public String bindings(@AuthenticationPrincipal CatapultOAuth2User principal,
                           @RequestParam(defaultValue = "0") int page,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) String source,
                           Model model) {
        UserAccount user = principal.getUserAccount();
        PageRequest pageRequest = PageRequest.of(page, 20);

        Page<GameBinding> bindings;
        if (status != null && !status.isBlank()) {
            bindings = gameBindingRepository.findByUserAndStatus(
                user, GameBinding.Status.valueOf(status.toUpperCase()), pageRequest);
        } else if (source != null && !source.isBlank()) {
            bindings = gameBindingRepository.findByUserAndSourceType(
                user, GameBinding.SourceType.valueOf(source.toUpperCase()), pageRequest);
        } else {
            bindings = gameBindingRepository.findByUser(user, pageRequest);
        }

        model.addAttribute("user", user);
        model.addAttribute("bindings", bindings);
        model.addAttribute("availableCcls", TwitchCcl.values());
        model.addAttribute("isPendingDeletion", user.getStatus() == UserAccount.Status.PENDING_DELETION);

        return "bindings";
    }

    @PostMapping("/bindings/{id}")
    public String updateBinding(@AuthenticationPrincipal CatapultOAuth2User principal,
                                @PathVariable UUID id,
                                @RequestParam(required = false) String twitchGameId,
                                @RequestParam(required = false) String twitchGameName,
                                @RequestParam(required = false, defaultValue = "false") boolean ignored,
                                @RequestParam(required = false) Set<String> ccls) {
        Set<TwitchCcl> cclSet = ccls == null ? Set.of() :
            ccls.stream().map(TwitchCcl::valueOf).collect(Collectors.toSet());

        bindingService.updateBinding(id, twitchGameId, twitchGameName, cclSet, ignored);
        return "redirect:/bindings";
    }

    @PostMapping("/bindings/{id}/delete")
    public String deleteBinding(@AuthenticationPrincipal CatapultOAuth2User principal,
                                @PathVariable UUID id) {
        bindingService.deleteBinding(id);
        return "redirect:/bindings";
    }
}
