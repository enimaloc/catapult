package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.GetterConfig;
import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.ProcessNames;
import fr.enimaloc.catapult.domain.ProcessPredicate;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.GetterConfigRepository;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.security.CatapultOAuth2User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Validated
@Slf4j
public class ObsSetupController {

    private final UserAccountRepository userAccountRepository;
    private final ProcessBindingRepository processBindingRepository;
    private final GetterConfigRepository getterConfigRepository;

    @Value("${app.public-url:http://localhost:8080}")
    private String publicUrl;

    @GetMapping("/fragments/obs-setup")
    public String fragment(@AuthenticationPrincipal CatapultOAuth2User principal, Model model) {
        UserAccount user = principal.getUserAccount();
        String apiKey = user.getApiKey();
        model.addAttribute("apiKey", apiKey);
        model.addAttribute("publicUrl", publicUrl);
        model.addAttribute("processBindings", processBindingRepository.findByUserOrderByProcessNameAsc(user));
        model.addAttribute("predicateTypes", ProcessPredicate.PredicateType.values());
        model.addAttribute("osTargets", ProcessPredicate.OsTarget.values());
        model.addAttribute("connectors", ProcessPredicate.Connector.values());
        if (apiKey != null) {
            model.addAttribute("obsScript", buildObsScript(apiKey));
        }
        return "fragments/obs-setup :: obs-setup";
    }

    @PostMapping("/obs/generate-key")
    public String generateApiKey(@AuthenticationPrincipal CatapultOAuth2User principal) {
        UserAccount user = principal.getUserAccount();
        user.setApiKey(generateSecureKey());
        userAccountRepository.save(user);
        log.info("API key generated for user {}", user.getId());
        ensureObsGetterConfig(user);
        return "redirect:/app";
    }

    @PostMapping("/obs/revoke-key")
    public String revokeApiKey(@AuthenticationPrincipal CatapultOAuth2User principal) {
        UserAccount user = principal.getUserAccount();
        user.setApiKey(null);
        userAccountRepository.save(user);
        log.info("API key revoked for user {}", user.getId());
        return "redirect:/app";
    }

    @PostMapping("/obs/process-bindings")
    public String addProcessBinding(@AuthenticationPrincipal CatapultOAuth2User principal,
                                    @RequestParam @NotBlank @Size(max = 255) String processName,
                                    @RequestParam @NotBlank @Size(max = 50) String twitchGameId,
                                    @RequestParam @Size(max = 255) String twitchGameName) {
        UserAccount user = principal.getUserAccount();
        ProcessBinding binding = new ProcessBinding();
        binding.setUser(user);
        binding.setProcessName(ProcessNames.normalize(processName));
        binding.setTwitchGameId(twitchGameId);
        binding.setTwitchGameName(twitchGameName);
        processBindingRepository.save(binding);
        return "redirect:/app";
    }

    @PostMapping("/obs/process-bindings/{id}/delete")
    public String deleteProcessBinding(@AuthenticationPrincipal CatapultOAuth2User principal,
                                       @PathVariable UUID id) {
        processBindingRepository.findById(id).ifPresent(pb -> {
            if (pb.getUser() != null && pb.getUser().getId().equals(principal.getUserAccount().getId())) {
                processBindingRepository.delete(pb);
            }
        });
        return "redirect:/app";
    }

    @PostMapping("/obs/process-bindings/{id}/predicates")
    public String addPredicate(@AuthenticationPrincipal CatapultOAuth2User principal,
                               @PathVariable UUID id,
                               @RequestParam @NotNull ProcessPredicate.PredicateType type,
                               @RequestParam @NotNull ProcessPredicate.Connector connector,
                               @RequestParam(required = false) @Size(max = 255) String key,
                               @RequestParam @NotBlank @Size(max = 500) String value,
                               @RequestParam @NotNull ProcessPredicate.OsTarget osTarget) {
        processBindingRepository.findById(id).ifPresent(pb -> {
            if (pb.getUser() == null || !pb.getUser().getId().equals(principal.getUserAccount().getId())) return;
            ProcessPredicate pred = new ProcessPredicate();
            pred.setBinding(pb);
            pred.setType(type);
            pred.setConnector(connector);
            pred.setKey(key);
            pred.setValue(value);
            pred.setOsTarget(osTarget);
            pred.setPosition(pb.getPredicates().size());
            pb.getPredicates().add(pred);
            processBindingRepository.save(pb);
        });
        return "redirect:/app";
    }

    @PostMapping("/obs/process-bindings/{id}/predicates/{predId}/delete")
    public String deletePredicate(@AuthenticationPrincipal CatapultOAuth2User principal,
                                  @PathVariable UUID id,
                                  @PathVariable UUID predId) {
        processBindingRepository.findById(id).ifPresent(pb -> {
            if (pb.getUser() == null || !pb.getUser().getId().equals(principal.getUserAccount().getId())) return;
            pb.getPredicates().removeIf(p -> p.getId().equals(predId));
            for (int i = 0; i < pb.getPredicates().size(); i++) {
                pb.getPredicates().get(i).setPosition(i);
            }
            processBindingRepository.save(pb);
        });
        return "redirect:/app";
    }

    private String buildObsScript(String apiKey) {
        return """
                # catapult_obs.py — Catapult OBS integration
                import obspython as obs
                import platform
                import os as _os
                import psutil
                import requests

                CATAPULT_URL = "%s/api/obs/processes"
                API_KEY      = "%s"
                INTERVAL_MS  = 10_000  # toutes les 10 secondes

                _OS_NAME = platform.system().upper()
                if _OS_NAME == "DARWIN":
                    _OS_NAME = "MACOS"

                def _collect_procs():
                    seen = set()
                    procs = []
                    for p in psutil.process_iter(["name"]):
                        try:
                            name = p.name()
                            if name in seen:
                                continue
                            seen.add(name)
                            entry = {"name": name}
                            try:
                                entry["workingDirectory"] = p.cwd()
                            except (psutil.AccessDenied, psutil.ZombieProcess):
                                pass
                            try:
                                entry["cmdline"] = " ".join(p.cmdline())
                            except (psutil.AccessDenied, psutil.ZombieProcess):
                                pass
                            procs.append(entry)
                        except (psutil.NoSuchProcess, psutil.AccessDenied):
                            pass
                    return procs

                def _send():
                    try:
                        requests.post(CATAPULT_URL,
                                      json={"os": _OS_NAME, "environment": dict(_os.environ), "processes": _collect_procs()},
                                      headers={"X-Api-Key": API_KEY}, timeout=5)
                    except Exception as e:
                        print(f"[Catapult] {e}")

                def script_load(settings):
                    obs.timer_add(_send, INTERVAL_MS)

                def script_unload():
                    obs.timer_remove(_send)

                def script_description():
                    return "Catapult — détection de jeu par processus.\"""".formatted(publicUrl, apiKey);
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
