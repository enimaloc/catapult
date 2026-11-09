package fr.enimaloc.catapult.api.admin;

import fr.enimaloc.catapult.api.ApiPaths;
import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.common.dto.admin.AddToGroupRequest;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMemberSummaryDto;
import fr.enimaloc.catapult.common.dto.admin.AdminMembersPage;
import fr.enimaloc.catapult.common.dto.admin.MemberTargetingDto;
import fr.enimaloc.catapult.common.dto.admin.MigrateRequest;
import fr.enimaloc.catapult.common.dto.admin.SetFlagRequest;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.premium.GrantPremiumRequest;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.service.account.AdminMemberService;
import fr.enimaloc.catapult.service.premium.PremiumService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.ADMIN + "/members")
@RequiredArgsConstructor
public class ApiAdminMembersController {

    private final AdminMemberService members;
    private final PremiumService premium;

    /**
     * One page of members as catapult-web's admin page reads them (not the entity: no tokens, no
     * proxies), filtered by {@code q} when it isn't blank. Live status is asked for this page only.
     */
    @GetMapping
    public AdminMembersPage page(@RequestParam(defaultValue = "0") int page,
                                 @RequestParam(required = false) String q) {
        return members.page(page, q);
    }

    /** At most 20 members matching {@code q} like the listing does, for a picker; no live status. */
    @GetMapping("/search")
    public List<AdminMemberDto> search(@RequestParam(required = false) String q) {
        return members.search(q);
    }

    @PostMapping("/{id}/bot/toggle")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void toggleBot(@PathVariable UUID id) {
        members.toggleBot(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        members.deleteAccount(id, () -> ApiUserResolver.requireJwt(jwt).getClaimAsString("twitchId"));
    }

    @DeleteMapping("/{id}/links/steam")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkSteam(@PathVariable UUID id) {
        members.unlinkSteam(id);
    }

    @DeleteMapping("/{id}/links/twitch")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkTwitch(@PathVariable UUID id) {
        members.unlinkTwitch(id);
    }

    @PostMapping("/{id}/migrate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void migrateData(@PathVariable UUID id, @RequestBody MigrateRequest body) {
        members.migrate(id, body);
    }

    /**
     * Marks a regular UserAccount as the system account (the bot for chat
     * commands). Any previous system account is unmarked; if it has no
     * Twitch identity, it is deleted to avoid leaving an orphan.
     */
    @PostMapping("/{id}/promote-to-system")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void promoteToSystem(@PathVariable UUID id) {
        members.promoteToSystem(id);
    }

    @GetMapping("/{id}")
    public AdminMemberSummaryDto getMember(@PathVariable UUID id) {
        return members.summary(id);
    }

    @PostMapping("/{id}/flags")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void setFlag(@PathVariable UUID id, @RequestBody SetFlagRequest body) {
        members.setFlag(id, body);
    }

    @DeleteMapping("/{id}/flags/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFlag(@PathVariable UUID id, @PathVariable String key) {
        members.deleteFlag(id, key);
    }

    @PostMapping("/{id}/groups")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void addToGroup(@PathVariable UUID id, @RequestBody AddToGroupRequest body) {
        members.addToGroup(id, body.groupKey());
    }

    @DeleteMapping("/{id}/groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFromGroup(@PathVariable UUID id, @PathVariable UUID groupId) {
        members.removeFromGroup(id, groupId);
    }

    @GetMapping("/{id}/targeting")
    public MemberTargetingDto targeting(@PathVariable UUID id) {
        return members.targeting(id);
    }

    /** Premium for the member until {@code body.until} (that day included), or for good without a date. */
    @PostMapping("/{id}/premium")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grantPremium(@PathVariable UUID id, @RequestBody(required = false) GrantPremiumRequest body,
                             @AuthenticationPrincipal Jwt jwt) {
        LocalDate until = body == null ? null : body.until();
        if (until != null && until.isBefore(premium.today())) {
            throw new ApiProblem(ErrorCode.INVALID, "The premium end date is in the past");
        }
        premium.grant(id, until, actor(jwt));
    }

    @DeleteMapping("/{id}/premium")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokePremium(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        premium.revoke(id, actor(jwt));
    }

    /** The admin's Twitch username, their account id when the token has none. */
    private static String actor(Jwt jwt) {
        Jwt token = ApiUserResolver.requireJwt(jwt);
        String username = token.getClaimAsString("username");
        return username != null ? username : token.getSubject();
    }
}
