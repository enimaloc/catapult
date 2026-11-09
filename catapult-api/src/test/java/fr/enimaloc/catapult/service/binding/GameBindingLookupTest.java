package fr.enimaloc.catapult.service.binding;

import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.domain.binding.GameBinding;
import fr.enimaloc.catapult.getter.DetectedGame;
import fr.enimaloc.catapult.repository.binding.GameBindingRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameBindingLookupTest {

    private final GameBindingRepository repository = mock(GameBindingRepository.class);
    private final GameBindingLookup lookup = new GameBindingLookup(repository);

    @Test
    void findByDetectedGame_returnsTheBindingForThatSource() {
        UserAccount user = new UserAccount();
        GameBinding binding = new GameBinding();
        when(repository.findByUserAndSourceIdAndSourceType(user, "730", GameBinding.SourceType.STEAM))
                .thenReturn(Optional.of(binding));

        assertThat(lookup.findByDetectedGame(user,
                new DetectedGame("730", GameBinding.SourceType.STEAM, "Counter-Strike 2"))).containsSame(binding);
        assertThat(lookup.findByDetectedGame(user,
                new DetectedGame("731", GameBinding.SourceType.STEAM, "Other"))).isEmpty();
    }
}
