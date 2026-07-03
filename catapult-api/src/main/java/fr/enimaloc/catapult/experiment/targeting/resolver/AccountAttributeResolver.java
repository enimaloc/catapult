package fr.enimaloc.catapult.experiment.targeting.resolver;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.experiment.targeting.AttributeResolver;
import fr.enimaloc.catapult.experiment.targeting.AttributeValue;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class AccountAttributeResolver implements AttributeResolver {

    private static final Set<String> KEYS = Set.of(
        "account_age_days", "has_steam", "has_twitch",
        "account_status", "is_system", "bot_enabled");

    @Override
    public boolean supports(String key) {
        return KEYS.contains(key);
    }

    @Override
    public AttributeValue resolve(UserAccount user, String key) {
        return switch (key) {
            case "account_age_days" -> AttributeValue.number(
                (System.currentTimeMillis() - user.getCreatedAt().toEpochMilli()) / 86_400_000.0);
            case "has_steam"        -> AttributeValue.number(user.getSteamId() != null ? 1 : 0);
            case "has_twitch"       -> AttributeValue.number(user.getTwitchId() != null ? 1 : 0);
            case "account_status"   -> AttributeValue.text(user.getStatus().name());
            case "is_system"        -> AttributeValue.number(user.isSystemAccount() ? 1 : 0);
            case "bot_enabled"      -> AttributeValue.number(user.isBotEnabled() ? 1 : 0);
            default                 -> AttributeValue.missing();
        };
    }
}
