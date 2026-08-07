package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.AlphaInvite;
import fr.enimaloc.catapult.domain.AlphaInviteRedemption;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.domain.WhitelistEntry;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.InviteService;
import fr.enimaloc.catapult.service.WhitelistService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/whitelist")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminWhitelistController {

    private final WhitelistService whitelistService;
    private final InviteService inviteService;
    private final UserAccountRepository userAccountRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public WhitelistPageData page() {
        List<WhitelistEntry> entries = whitelistService.findAll();
        Map<String, String> resolvedUsernames = entries.stream()
                .collect(Collectors.toMap(
                        WhitelistEntry::getTwitchId,
                        e -> userAccountRepository.findByTwitchId(e.getTwitchId())
                                .map(UserAccount::getTwitchUsername)
                                .orElse("—")
                ));

        List<AlphaInvite> invites = inviteService.findAll();
        List<InviteRow> inviteRows = invites.stream().map(inv -> {
            List<AlphaInviteRedemption> redemptions = inviteService.getRedemptions(inv);
            return new InviteRow(
                inv.getId(),
                inv.getOwner().getId(),
                inv.getOwner().getTwitchUsername(),
                inv.getCode(),
                inv.getMaxUses(),
                inv.getUseCount(),
                inv.getCanReinvite(),
                inv.getCreatedAt(),
                redemptions.stream()
                    .map(r -> new RedemptionDto(r.getInviteeTwitchId(), r.getRedeemedAt()))
                    .toList()
            );
        }).toList();

        return new WhitelistPageData(
            entries, resolvedUsernames, whitelistService.isEnabled(),
            inviteService.isInviteEnabled(),
            inviteService.getGlobalMaxMembers().orElse(null),
            inviteService.getDefaultMaxUses().orElse(null),
            inviteService.getDefaultCanReinvite(),
            inviteService.isGlobalCapReached(),
            inviteRows
        );
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

    @PostMapping("/invite/toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleInvites() {
        inviteService.setInviteEnabled(!inviteService.isInviteEnabled());
    }

    @PostMapping("/invite/settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateInviteSettings(@RequestBody InviteSettingsRequest body) {
        inviteService.setGlobalMaxMembers(body.globalMaxMembers());
        inviteService.setDefaultMaxUses(body.defaultMaxUses());
        inviteService.setDefaultCanReinvite(body.defaultCanReinvite());
    }

    @PostMapping("/invite/{inviteId}/quota")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateInviteQuota(@PathVariable UUID inviteId, @RequestBody QuotaRequest body) {
        inviteService.updateInviteQuota(inviteId, body.maxUses(), body.canReinvite());
    }

    public record WhitelistPageData(
        List<WhitelistEntry> entries, Map<String, String> resolvedUsernames, boolean whitelistEnabled,
        boolean inviteEnabled, Integer globalMaxMembers, Integer defaultMaxUses,
        boolean defaultCanReinvite, boolean globalCapReached, List<InviteRow> invites) {}

    public record InviteRow(UUID id, UUID ownerId, String ownerUsername, String code,
                            Integer maxUses, int useCount, Boolean canReinvite,
                            Instant createdAt, List<RedemptionDto> redemptions) {}

    public record RedemptionDto(String inviteeTwitchId, Instant redeemedAt) {}

    public record AddRequest(String twitchId) {}

    public record InviteSettingsRequest(Integer globalMaxMembers, Integer defaultMaxUses,
                                        boolean defaultCanReinvite) {}

    public record QuotaRequest(Integer maxUses, Boolean canReinvite) {}
}
