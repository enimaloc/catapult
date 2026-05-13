package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.GetterConfig;
import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class ObsSetupController {

    private final UserAccountRepository userAccountRepository;
    private final ProcessBindingRepository processBindingRepository;
    private final GetterConfigRepository getterConfigRepository;

    @Value("${app.public-url:http://localhost:8080}")
    private String publicUrl;

    @GetMapping("/fragments/obs-setup")
    public String fragment(@AuthenticationPrincipal CatapultOAuth2User principal, Model model) {
        UserAccount user = principal.getUserAccount();
        model.addAttribute("apiKey", user.getApiKey());
        model.addAttribute("publicUrl", publicUrl);
        model.addAttribute("processBindings", processBindingRepository.findByUserOrderByProcessNameAsc(user));
        return "fragments/obs-setup :: obs-setup";
    }

    @PostMapping("/obs/generate-key")
    public String generateApiKey(@AuthenticationPrincipal CatapultOAuth2User principal) {
        UserAccount user = principal.getUserAccount();
        user.setApiKey(generateSecureKey());
        userAccountRepository.save(user);
        ensureObsGetterConfig(user);
        return "redirect:/app";
    }

    @PostMapping("/obs/revoke-key")
    public String revokeApiKey(@AuthenticationPrincipal CatapultOAuth2User principal) {
        UserAccount user = principal.getUserAccount();
        user.setApiKey(null);
        userAccountRepository.save(user);
        return "redirect:/app";
    }

    @PostMapping("/obs/process-bindings")
    public String addProcessBinding(@AuthenticationPrincipal CatapultOAuth2User principal,
                                    @RequestParam String processName,
                                    @RequestParam String twitchGameId,
                                    @RequestParam String twitchGameName) {
        UserAccount user = principal.getUserAccount();
        ProcessBinding binding = new ProcessBinding();
        binding.setUser(user);
        binding.setProcessName(processName.strip());
        binding.setTwitchGameId(twitchGameId);
        binding.setTwitchGameName(twitchGameName);
        processBindingRepository.save(binding);
        return "redirect:/app";
    }

    @PostMapping("/obs/process-bindings/{id}/delete")
    public String deleteProcessBinding(@AuthenticationPrincipal CatapultOAuth2User principal,
                                       @PathVariable UUID id) {
        processBindingRepository.findById(id).ifPresent(pb -> {
            if (pb.getUser().getId().equals(principal.getUserAccount().getId())) {
                processBindingRepository.delete(pb);
            }
        });
        return "redirect:/app";
    }

    private void ensureObsGetterConfig(UserAccount user) {
        if (getterConfigRepository.findByUserAndProvider(user, GetterConfig.Provider.OBS).isEmpty()) {
            List<GetterConfig> existing = getterConfigRepository.findByUserOrderByPriorityAsc(user);
            int nextPriority = existing.stream()
                    .mapToInt(GetterConfig::getPriority)
                    .max().orElse(0) + 1;

            GetterConfig config = new GetterConfig();
            config.setUser(user);
            config.setProvider(GetterConfig.Provider.OBS);
            config.setPriority(nextPriority);
            config.setEnabled(true);
            getterConfigRepository.save(config);
        }
    }

    private static String generateSecureKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
