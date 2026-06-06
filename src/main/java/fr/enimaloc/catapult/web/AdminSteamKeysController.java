package fr.enimaloc.catapult.web;

import fr.enimaloc.catapult.domain.SteamApiKeyEntry;
import fr.enimaloc.catapult.getter.SteamApiKeyRotator;
import fr.enimaloc.catapult.repository.SteamApiKeyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/admin/steam-keys")
public class AdminSteamKeysController {

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
        model.addAttribute("entries", repository.findByExclusiveFalse());
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
}
