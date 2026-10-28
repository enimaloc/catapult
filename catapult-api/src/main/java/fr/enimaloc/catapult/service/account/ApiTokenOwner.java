package fr.enimaloc.catapult.service.account;

import fr.enimaloc.catapult.domain.account.ApiTokenScope;
import fr.enimaloc.catapult.domain.account.UserAccount;

/** The member a personal API token belongs to, and the scope that token carries. */
public record ApiTokenOwner(UserAccount user, ApiTokenScope scope) {
}
