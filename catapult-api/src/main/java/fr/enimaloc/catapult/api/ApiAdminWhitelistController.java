package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.WhitelistEntry;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.WhitelistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/whitelist")
@RequiredArgsConstructor
public class ApiAdminWhitelistController {

    private final WhitelistService whitelistService;
    private final UserAccountRepository userAccountRepository;

    @GetMapping
    public WhitelistPageData page() {
        List<WhitelistEntry> entries = whitelistService.findAll();
        Map<String, String> resolvedUsernames = entries.stream()
                .collect(Collectors.toMap(
                        WhitelistEntry::getTwitchId,
                        e -> userAccountRepository.findByTwitchId(e.getTwitchId())
                                .map(UserAccount::getTwitchUsername)
                                .orElse("—")
                ));
        return new WhitelistPageData(entries, resolvedUsernames, whitelistService.isEnabled());
    }

    @PostMapping("/toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggle() {
        whitelistService.toggle();
    }

    @PostMapping("/add")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void add(@RequestBody AddRequest body) {
        whitelistService.add(body.twitchId().trim());
    }

    @PostMapping("/{id}/delete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        whitelistService.remove(id);
    }

    public record WhitelistPageData(List<WhitelistEntry> entries, Map<String, String> resolvedUsernames, boolean whitelistEnabled) {}

    public record AddRequest(String twitchId) {}
}
