package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.WhitelistEntry;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.WhitelistService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/admin/whitelist")
@RequiredArgsConstructor
public class AdminWhitelistController {

    private final WhitelistService whitelistService;
    private final UserAccountRepository userAccountRepository;

    @GetMapping
    public String page(Model model) {
        List<WhitelistEntry> entries = whitelistService.findAll();
        Map<String, String> resolvedUsernames = entries.stream()
            .collect(Collectors.toMap(
                WhitelistEntry::getTwitchId,
                e -> userAccountRepository.findByTwitchId(e.getTwitchId())
                    .map(u -> u.getTwitchUsername())
                    .orElse("—")
            ));
        model.addAttribute("entries", entries);
        model.addAttribute("resolvedUsernames", resolvedUsernames);
        model.addAttribute("whitelistEnabled", whitelistService.isEnabled());
        return "admin/whitelist";
    }

    @PostMapping("/toggle")
    public String toggle() {
        whitelistService.setEnabled(!whitelistService.isEnabled());
        return "redirect:/admin/whitelist";
    }

    @PostMapping("/add")
    public String add(@RequestParam String twitchId) {
        whitelistService.add(twitchId.trim());
        return "redirect:/admin/whitelist";
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id) {
        whitelistService.remove(id);
        return "redirect:/admin/whitelist";
    }
}
