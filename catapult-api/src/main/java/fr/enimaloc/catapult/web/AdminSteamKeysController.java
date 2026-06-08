package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.SteamApiKeyEntry;
import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import fr.enimaloc.catapult.repository.SteamApiKeyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Controller
@RequestMapping("/admin/steam-keys")
public class AdminSteamKeysController {

    public record KeyStatus(String masked, String owner, boolean blocked, long blockedForSeconds) {}

    private final SteamApiKeyRepository repository;
    private final SteamApiKeyRotator rotator;

    public AdminSteamKeysController(SteamApiKeyRepository repository) {
        this(repository, null);
    }

    @Autowired
    public AdminSteamKeysController(SteamApiKeyRepository repository,
                                    @Autowired(required = false) SteamApiKeyRotator rotator) {
        this.repository = repository;
        this.rotator = rotator;
    }

    @GetMapping
    public String page(Model model) {
        List<SteamApiKeyEntry> entries = repository.findByExclusiveFalseWithOwner();
        Map<String, Long> blockedUntil = rotator != null ? rotator.getKeyBlockedUntil() : Map.of();
        long now = System.currentTimeMillis();

        Map<String, KeyStatus> keyStatuses = new LinkedHashMap<>();
        for (SteamApiKeyEntry entry : entries) {
            String key = entry.getApiKey();
            String masked = key.length() > 8
                ? key.substring(0, 4) + "…" + key.substring(key.length() - 4)
                : "…";
            String owner = entry.getOwner() != null ? entry.getOwner().getTwitchUsername() : null;
            long until = blockedUntil.getOrDefault(key, 0L);
            boolean blocked = until > now;
            long remainingSec = blocked ? TimeUnit.MILLISECONDS.toSeconds(until - now) : 0L;
            keyStatuses.put(key, new KeyStatus(masked, owner, blocked, remainingSec));
        }

        model.addAttribute("keyStatuses", keyStatuses);
        model.addAttribute("steamEnabled", rotator != null);
        return "admin/steam-keys";
    }

    @PostMapping("/add")
    public String add(@RequestParam String apiKey) {
        String trimmed = apiKey.trim();
        if (!trimmed.matches("[0-9A-Fa-f]{32}")) {
            return "redirect:/admin/steam-keys?error=invalid";
        }
        if (!repository.existsById(trimmed)) {
            repository.save(new SteamApiKeyEntry(trimmed));
            if (rotator != null) rotator.refreshKeys();
        }
        return "redirect:/admin/steam-keys";
    }

    @PostMapping("/delete")
    public String delete(@RequestParam String apiKey) {
        repository.deleteById(apiKey);
        if (rotator != null) rotator.refreshKeys();
        return "redirect:/admin/steam-keys";
    }

    @PostMapping("/refresh")
    public String refresh() {
        if (rotator != null) rotator.refreshKeys();
        return "redirect:/admin/steam-keys";
    }
}
