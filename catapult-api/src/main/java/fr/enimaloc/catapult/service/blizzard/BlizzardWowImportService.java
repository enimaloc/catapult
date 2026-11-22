package fr.enimaloc.catapult.service.blizzard;

import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.error.ErrorCode;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.battlenet.BattleNetFriendLink;
import fr.enimaloc.catapult.repository.battlenet.BattleNetFriendLinkRepository;
import fr.enimaloc.catapult.service.blizzard.BlizzardAccountService.ImportedCharacter;
import fr.enimaloc.catapult.service.blizzard.BlizzardImportRefusedException.Reason;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardClient;
import fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardRegion;
import fr.enimaloc.catapult.service.enrichment.blizzard.WowUserCharacter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * Imports a member's World of Warcraft characters with the user token of a {@code wow.profile} consent
 * ({@code battlenet-stats}). The token is only a parameter: never stored, logged nor put in an exception. The consent
 * must come from the member's linked Battle.net account, and every region must answer: a partial read would wipe the
 * characters of the region that failed, so it imports nothing. Every request is charged to the Blizzard limiter by the
 * client (userinfo + 4 regions).
 */
@Slf4j
@Service
@ConditionalOnBooleanProperty("battlenet.enabled")
public class BlizzardWowImportService {

    private final BlizzardClient client;
    private final BlizzardAccountService accounts;
    private final BattleNetFriendLinkRepository links;
    private final Clock clock;

    @Autowired
    public BlizzardWowImportService(BlizzardClient client, BlizzardAccountService accounts,
                                    BattleNetFriendLinkRepository links) {
        this(client, accounts, links, Clock.systemUTC());
    }

    BlizzardWowImportService(BlizzardClient client, BlizzardAccountService accounts,
                             BattleNetFriendLinkRepository links, Clock clock) {
        this.client   = client;
        this.accounts = accounts;
        this.links    = links;
        this.clock    = clock;
    }

    /**
     * Replaces the member's characters by those of the four regions.
     *
     * @return how many characters were read
     * @throws BlizzardImportRefusedException no Battle.net link, or a consent from another Battle.net account
     * @throws fr.enimaloc.catapult.service.enrichment.blizzard.BlizzardUnavailableException a request failed (nothing
     *         was replaced)
     */
    public int importFor(UserAccount user, String userAccessToken) {
        BattleNetFriendLink link = links.findByUserId(user.getId())
                .filter(found -> found.getStatus() != BattleNetFriendLink.Status.REMOVED)
                .orElseThrow(() -> new BlizzardImportRefusedException(Reason.NOT_LINKED));
        if (client.userAccountId(userAccessToken) != link.getBnetAccountId()) {
            log.warn("World of Warcraft import of {} refused: the consent is not the linked Battle.net account",
                    user.getId());
            throw new BlizzardImportRefusedException(Reason.OTHER_ACCOUNT);
        }
        List<ImportedCharacter> imported = new ArrayList<>();
        // Any failed region throws before anything is written
        for (BlizzardRegion region : BlizzardRegion.values()) {
            for (WowUserCharacter c : client.wowUserCharacters(region, userAccessToken)) {
                if (c.realmSlug() == null) {
                    continue; // not readable afterwards
                }
                imported.add(new ImportedCharacter(region, c.id(), c.realmSlug(),
                        c.realmName() == null ? c.realmSlug() : c.realmName(), c.name(), c.level(),
                        c.className() == null ? "" : c.className(), c.faction() == null ? "" : c.faction()));
            }
        }
        try {
            accounts.replaceWowCharacters(user.getId(), imported, clock.instant());
        } catch (ApiProblem problem) {
            if (problem.code() == ErrorCode.NOT_FOUND) {
                // The link went away meanwhile
                throw new BlizzardImportRefusedException(Reason.NOT_LINKED);
            }
            throw problem;
        }
        log.info("World of Warcraft characters imported for {}: {}", user.getId(), imported.size());
        return imported.size();
    }
}
