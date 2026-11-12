package fr.enimaloc.catapult.service.title;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.repository.account.UserAccountRepository;
import fr.enimaloc.catapult.service.drops.DropsGate;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The title help's parts an account is offered: the drops only with the conditions, nothing for an unknown one. */
class TitleHelpSectionsTest {

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final StreamTitleService streamTitle = mock(StreamTitleService.class);
    private final DropsGate drops = mock(DropsGate.class);
    private final TitleHelpSections sections = new TitleHelpSections(accounts, streamTitle, drops);
    private final UUID id = UUID.randomUUID();
    private final UserAccount account = new UserAccount();

    @Test
    void theDrops_needTheConditions() {
        when(accounts.findById(id)).thenReturn(Optional.of(account));
        when(drops.isAvailableReadOnly(account)).thenReturn(true);

        when(streamTitle.conditionsAvailableReadOnly(account)).thenReturn(true);
        assertThat(sections.forAccount(id)).isEqualTo(new TitleHelpSections.Offered(true, true));
        when(streamTitle.conditionsAvailableReadOnly(account)).thenReturn(false);
        assertThat(sections.forAccount(id)).isEqualTo(TitleHelpSections.Offered.NONE);
    }

    @Test
    void anUnknownAccount_isOfferedNothing() {
        when(accounts.findById(id)).thenReturn(Optional.empty());

        assertThat(sections.forAccount(id)).isEqualTo(TitleHelpSections.Offered.NONE);
    }
}
