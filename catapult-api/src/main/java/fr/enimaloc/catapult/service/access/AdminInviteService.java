package fr.enimaloc.catapult.service.access;

import fr.enimaloc.catapult.common.dto.admin.AdminInvitePageData;
import fr.enimaloc.catapult.common.dto.admin.AdminInviteRow;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.AdminWhitelistRedemptionDto;
import fr.enimaloc.catapult.common.dto.admin.WhitelistInviteRow;
import fr.enimaloc.catapult.domain.access.AlphaInvite;
import fr.enimaloc.catapult.domain.access.AlphaInviteRedemption;
import fr.enimaloc.catapult.domain.access.WhitelistEntry;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The admin's invite and whitelist pages, and the invites it grants. */
@Service
@RequiredArgsConstructor
public class AdminInviteService {

    private final WhitelistService whitelistService;
    private final InviteService inviteService;
    private final UserAccountRepository userAccountRepository;

    /** Every invite with its owner and redemptions, and the active human members who have none. */
    public AdminInvitePageData invitePage() {
        List<AlphaInvite> invites = inviteService.findAll();
        Set<UUID> ownersWithInvite = invites.stream()
            .map(inv -> inv.getOwner().getId())
            .collect(Collectors.toSet());

        List<AdminInviteRow> rows = invites.stream().map(inv -> {
            UserAccount owner = inv.getOwner();
            List<AlphaInviteRedemption> redemptions = inviteService.getRedemptions(inv);
            return new AdminInviteRow(
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
                    .map(r -> new AdminWhitelistRedemptionDto(r.getInviteeTwitchId(), r.getRedeemedAt()))
                    .toList()
            );
        }).toList();

        List<AdminMemberSummaryDto> membersWithoutInvite = userAccountRepository.findAll().stream()
            .filter(u -> !u.isSystemAccount() && u.getStatus() == UserAccount.Status.ACTIVE)
            .filter(u -> !ownersWithInvite.contains(u.getId()))
            .map(u -> new AdminMemberSummaryDto(u.getId(), u.getTwitchUsername()))
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

    /** The whitelist with each entry's username, and every invite named by its (lazy) owner, read in here. */
    @Transactional(readOnly = true)
    public WhitelistPageData whitelistPage() {
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

    public void grant(UUID memberId) {
        UserAccount user = userAccountRepository.findById(memberId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        inviteService.grantInvite(user);
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
