package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.ProcessPredicate;
import fr.enimaloc.catapult.repository.ProcessBindingRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Controller
@RequestMapping("/admin/process-rules")
@RequiredArgsConstructor
@Validated
@Slf4j
public class AdminProcessRuleController {

    private final ProcessBindingRepository processBindingRepository;

    @GetMapping
    public String index(Model model) {
        model.addAttribute("globalRules", processBindingRepository.findByUserIsNull());
        model.addAttribute("predicateTypes", ProcessPredicate.PredicateType.values());
        model.addAttribute("osTargets", ProcessPredicate.OsTarget.values());
        model.addAttribute("connectors", ProcessPredicate.Connector.values());
        return "admin/global-process-rules";
    }

    @PostMapping
    public String addRule(
            @RequestParam @NotBlank @Size(max = 255) String processName,
            @RequestParam(defaultValue = "false") boolean isRegex,
            @RequestParam @NotBlank @Size(max = 50) String twitchGameId,
            @RequestParam(required = false) @Size(max = 255) String twitchGameName,
            RedirectAttributes redirectAttributes) {

        if (isRegex) {
            try {
                Pattern.compile(processName);
            } catch (PatternSyntaxException e) {
                redirectAttributes.addFlashAttribute("error", "Pattern regex invalide : " + e.getMessage());
                return "redirect:/admin/process-rules";
            }
        }

        ProcessBinding rule = new ProcessBinding();
        rule.setProcessName(processName);
        rule.setRegex(isRegex);
        rule.setTwitchGameId(twitchGameId);
        rule.setTwitchGameName(twitchGameName);
        processBindingRepository.save(rule);
        log.info("Global process rule added: pattern='{}' regex={} game='{}'", processName, isRegex, twitchGameName);
        return "redirect:/admin/process-rules";
    }

    @PostMapping("/{id}/delete")
    public String deleteRule(@PathVariable UUID id) {
        processBindingRepository.findById(id).ifPresent(rule -> {
            if (rule.isGlobal()) {
                processBindingRepository.delete(rule);
                log.info("Global process rule deleted: id={}", id);
            }
        });
        return "redirect:/admin/process-rules";
    }

    @PostMapping("/{id}/predicates")
    public String addPredicate(
            @PathVariable UUID id,
            @RequestParam @NotNull ProcessPredicate.PredicateType type,
            @RequestParam @NotNull ProcessPredicate.Connector connector,
            @RequestParam(required = false) @Size(max = 255) String key,
            @RequestParam @NotBlank @Size(max = 500) String value,
            @RequestParam @NotNull ProcessPredicate.OsTarget osTarget) {

        processBindingRepository.findById(id).ifPresent(rule -> {
            if (!rule.isGlobal()) return;
            ProcessPredicate pred = new ProcessPredicate();
            pred.setBinding(rule);
            pred.setType(type);
            pred.setConnector(connector);
            pred.setKey(key);
            pred.setValue(value);
            pred.setOsTarget(osTarget);
            pred.setPosition(rule.getPredicates().size());
            rule.getPredicates().add(pred);
            processBindingRepository.save(rule);
        });
        return "redirect:/admin/process-rules";
    }

    @PostMapping("/{id}/predicates/{predId}/delete")
    public String deletePredicate(@PathVariable UUID id, @PathVariable UUID predId) {
        processBindingRepository.findById(id).ifPresent(rule -> {
            if (!rule.isGlobal()) return;
            rule.getPredicates().removeIf(p -> p.getId().equals(predId));
            for (int i = 0; i < rule.getPredicates().size(); i++) {
                rule.getPredicates().get(i).setPosition(i);
            }
            processBindingRepository.save(rule);
        });
        return "redirect:/admin/process-rules";
    }
}
