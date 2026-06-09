package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.client.ApiClient;
import fr.enimaloc.catapult.client.ApiHealthService;
import fr.enimaloc.catapult.security.CatapultWebUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.info.BuildProperties;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@ControllerAdvice
@RequiredArgsConstructor
public class GlobalModelAdvice {

    private final Optional<BuildProperties> buildProperties;
    private final ApiClient apiClient;
    private final ApiHealthService apiHealthService;

    @ModelAttribute
    public void addGlobalFlags(Model model) {
        model.addAttribute("apiAvailable", apiHealthService.isAvailable());
    }

    @ModelAttribute
    public void addBuildInfo(Model model) {
        model.addAttribute("appVersion", buildProperties.map(BuildProperties::getVersion).orElse("dev"));
        model.addAttribute("git", new GitData(
                buildProperties.map(p -> p.get("git.branch")).orElse("unknown"),
                buildProperties.map(p -> p.get("git.commit")).orElse("unknown"),
                buildProperties.map(p -> p.get("git.repository-url"))
                        .filter(url -> url.startsWith("https://"))
                        .orElse(null)
        ));
    }

    @ModelAttribute
    @SuppressWarnings("unchecked")
    public void commonAttribute(@AuthenticationPrincipal CatapultWebUser principal, Model model) {
        model.addAttribute("user", principal);
        model.addAttribute("isPendingDeletion",
                principal != null && "PENDING_DELETION".equals(principal.getStatus()));

        try {
            Map<?, ?> raw = apiClient.get("/api/config/app", Map.class);
            if (raw != null) {
                String name = (String) raw.get("name");
                Number delay = (Number) raw.get("deletionDelayDays");
                int deletionDelayDays = delay != null ? delay.intValue() : 7;
                @SuppressWarnings("unchecked")
                List<String> gettersName = (List<String>) raw.get("gettersName");
                model.addAttribute("app", new App(
                        name != null ? name : "Catapult",
                        deletionDelayDays,
                        gettersName != null ? gettersName : List.of()
                ));
            } else {
                model.addAttribute("app", new App("Catapult", 7, List.of()));
            }
        } catch (Exception e) {
            log.warn("Could not fetch app config from catapult-api: {}", e.getMessage());
            model.addAttribute("app", new App("Catapult", 7, List.of()));
        }
    }

    public record GitData(String branch, String commit, String repositoryUrl) {}

    public record App(String name, int deletionDelayDays, List<String> gettersName) {
        public Account account() { return new Account(deletionDelayDays); }
        public record Account(int deletionDelayDays) {}
    }
}
