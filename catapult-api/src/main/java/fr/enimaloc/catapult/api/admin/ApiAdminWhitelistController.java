package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.common.dto.admin.AddRequest;
import fr.enimaloc.catapult.common.dto.admin.AdminWhitelistQuotaRequest;
import fr.enimaloc.catapult.common.dto.admin.AdminWhitelistRedemptionDto;
import fr.enimaloc.catapult.common.dto.admin.InviteSettingsRequest;
import fr.enimaloc.catapult.common.dto.admin.WhitelistInviteRow;
import fr.enimaloc.catapult.domain.access.AlphaInvite;
import fr.enimaloc.catapult.domain.access.WhitelistEntry;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.access.InviteService;
import fr.enimaloc.catapult.service.access.WhitelistService;
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

        List<WhitelistInviteRow> inviteRows = inviteService.findAll().stream().map(this::toRow).toList();

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
    public void updateInviteQuota(@PathVariable UUID inviteId, @RequestBody AdminWhitelistQuotaRequest body) {
        inviteService.updateInviteQuota(inviteId, body.maxUses(), body.canReinvite());
    }

    /** An invite with its owner and every redemption so far. */
    private WhitelistInviteRow toRow(AlphaInvite invite) {
        List<AdminWhitelistRedemptionDto> redemptions = inviteService.getRedemptions(invite).stream()
            .map(r -> new AdminWhitelistRedemptionDto(r.getInviteeTwitchId(), r.getRedeemedAt()))
            .toList();
        return new WhitelistInviteRow(
            invite.getId(),
            invite.getOwner().getId(),
            invite.getOwner().getTwitchUsername(),
            invite.getCode(),
            invite.getMaxUses(),
            invite.getUseCount(),
            invite.getCanReinvite(),
            invite.getCreatedAt(),
            redemptions);
    }

    public record WhitelistPageData(
        List<WhitelistEntry> entries, Map<String, String> resolvedUsernames, boolean whitelistEnabled,
        boolean inviteEnabled, Integer globalMaxMembers, Integer defaultMaxUses,
        boolean defaultCanReinvite, boolean globalCapReached, List<WhitelistInviteRow> invites) {}
}
