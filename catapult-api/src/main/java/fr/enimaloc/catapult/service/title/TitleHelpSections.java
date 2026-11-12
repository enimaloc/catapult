package fr.enimaloc.catapult.service.title;

import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.drops.DropsGate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Which parts of the title's help an account is offered, as its title fields are: the conditions (experiment
 * stream.title.conditions), and the drops with them (experiment drops). Read-only: the settings page enrols.
 */
@Service
@RequiredArgsConstructor
public class TitleHelpSections {

    /** The help's parts offered; the drops never without the conditions, as the title reads them. */
    public record Offered(boolean conditions, boolean drops) {
        public static final Offered NONE = new Offered(false, false);
    }

    private final UserAccountRepository accounts;
    private final StreamTitleService streamTitleService;
    private final DropsGate dropsGate;

    /** The parts offered to the account {@code accountId}; none for an unknown one. */
    public Offered forAccount(UUID accountId) {
        return accounts.findById(accountId).map(account -> {
            boolean conditions = streamTitleService.conditionsAvailableReadOnly(account);
            return new Offered(conditions, conditions && dropsGate.isAvailableReadOnly(account));
        }).orElse(Offered.NONE);
    }
}
