package fr.enimaloc.catapult.experiment.targeting;

import fr.enimaloc.catapult.domain.UserAccount;

public interface AttributeResolver {
    boolean supports(String key);
    AttributeValue resolve(UserAccount user, String key);
}
