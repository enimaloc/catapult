package fr.enimaloc.catapult.api;

import fr.enimaloc.catapult.domain.AlphaInvite;
import fr.enimaloc.catapult.domain.AlphaInviteRedemption;
import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.repository.UserAccountRepository;
import fr.enimaloc.catapult.service.InviteService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/invite")
@PreAuthorize("hasRole('ADMIN') or hasIpAddress('127.0.0.1') or hasIpAddress('::1')")
@RequiredArgsConstructor
public class ApiAdminInviteController {

    private final InviteService inviteService;
    private final UserAccountRepository userAccountRepository;

    @GetMapping
    public AdminInvitePageData page() {
        List<AlphaInvite> invites = inviteService.findAll();
        Set<UUID> ownersWithInvite = invites.stream()
            .map(inv -> inv.getOwner().getId())
            .collect(Collectors.toSet());

        List<InviteRow> rows = invites.stream().map(inv -> {
            UserAccount owner = inv.getOwner();
            List<AlphaInviteRedemption> redemptions = inviteService.getRedemptions(inv);
            return new InviteRow(
                inv.getId(),
                owner.getId(),
                owner.getTwitchUsername(),
                inv.getCode(),
                inv.getMaxUses(),
                inv.getUseCount(),
                inv.getCanReinvite(),
                inv.getCreatedAt(),
                inv.getRegeneratedAt(),
                redemptions.stream()
                    .map(r -> new RedemptionDto(r.getInviteeTwitchId(), r.getRedeemedAt()))
                    .toList()
            );
        }).toList();

        List<MemberDto> membersWithoutInvite = userAccountRepository.findAll().stream()
            .filter(u -> !u.isSystemAccount() && u.getStatus() == UserAccount.Status.ACTIVE)
            .filter(u -> !ownersWithInvite.contains(u.getId()))
            .map(u -> new MemberDto(u.getId(), u.getTwitchUsername()))
            .toList();

        return new AdminInvitePageData(
            rows,
            membersWithoutInvite,
            inviteService.getGlobalMaxMembers().orElse(null),
            inviteService.getDefaultMaxUses().orElse(null),
            inviteService.getDefaultCanReinvite(),
            inviteService.isGlobalCapReached()
        );
    }

    @PostMapping("/settings")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateSettings(@RequestBody GlobalSettingsRequest body) {
        inviteService.setGlobalMaxMembers(body.globalMaxMembers());
        inviteService.setDefaultMaxUses(body.defaultMaxUses());
        inviteService.setDefaultCanReinvite(body.defaultCanReinvite());
    }

    @PostMapping("/{inviteId}/quota")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateQuota(@PathVariable UUID inviteId, @RequestBody QuotaRequest body) {
        inviteService.updateInviteQuota(inviteId, body.maxUses(), body.canReinvite());
    }

    @PostMapping("/grant/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grantInvite(@PathVariable UUID userId) {
        UserAccount user = userAccountRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        inviteService.grantInvite(user);
    }

    public record AdminInvitePageData(List<InviteRow> invites, List<MemberDto> membersWithoutInvite,
                                      Integer globalMaxMembers, Integer defaultMaxUses,
                                      boolean defaultCanReinvite, boolean globalCapReached) {}

    public record InviteRow(UUID id, UUID ownerId, String ownerUsername, String code,
                            Integer maxUses, int useCount, Boolean canReinvite,
                            Instant createdAt, Instant regeneratedAt,
                            List<RedemptionDto> redemptions) {}

    public record MemberDto(UUID id, String twitchUsername) {}

    public record RedemptionDto(String inviteeTwitchId, Instant redeemedAt) {}

    public record GlobalSettingsRequest(Integer globalMaxMembers, Integer defaultMaxUses,
                                        boolean defaultCanReinvite) {}

    public record QuotaRequest(Integer maxUses, Boolean canReinvite) {}
}
