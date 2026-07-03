package fr.enimaloc.catapult.experiment.targeting.resolver;

import fr.enimaloc.catapult.domain.UserAccount;
import fr.enimaloc.catapult.experiment.targeting.AttributeResolver;
import fr.enimaloc.catapult.experiment.targeting.AttributeValue;
import fr.enimaloc.catapult.repository.UserFlagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class FlagResolver implements AttributeResolver {

    private static final String PREFIX = "flag:";

    private final UserFlagRepository flagRepository;

    @Override
    public boolean supports(String key) {
        return key.startsWith(PREFIX) && key.length() > PREFIX.length();
    }

    @Override
    public AttributeValue resolve(UserAccount user, String key) {
        String flagKey = key.substring(PREFIX.length());
        return flagRepository.findByUserAndFlagKey(user, flagKey)
            .map(f -> AttributeValue.text(f.getFlagValue()))
            .orElse(AttributeValue.missing());
    }
}
