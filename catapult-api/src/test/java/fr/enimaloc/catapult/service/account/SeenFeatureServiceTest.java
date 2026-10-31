package fr.enimaloc.catapult.service.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.enimaloc.catapult.repository.feature.SeenFeatureRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.transaction.annotation.Transactional;

class SeenFeatureServiceTest {

  private final SeenFeatureRepository repository = mock(SeenFeatureRepository.class);
  private final SeenFeatureService service = new SeenFeatureService(repository);
  private final UUID user = UUID.randomUUID();

  @Test
  void seen_listsTheKeysOfTheUser() {
    when(repository.findFeatureKeysByUserId(user)).thenReturn(List.of("channel.commands"));

    assertThat(service.seen(user)).containsExactly("channel.commands");
  }

  @Test
  void markSeen_insertsTheKeyIfAbsent() {
    service.markSeen(user, "channel.commands");

    verify(repository).insertIfAbsent(org.mockito.ArgumentMatchers.eq(user), org.mockito.ArgumentMatchers.eq("channel.commands"), any());
  }

  @Test
  void markSeen_runsInATransaction() throws Exception {
    var method = SeenFeatureService.class.getMethod("markSeen", UUID.class, String.class);

    assertThat(MergedAnnotations.from(method).isPresent(Transactional.class)).isTrue();
  }
}
