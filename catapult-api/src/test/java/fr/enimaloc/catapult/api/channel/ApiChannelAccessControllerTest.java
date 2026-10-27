package fr.enimaloc.catapult.api.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import fr.enimaloc.catapult.service.account.ChannelAccessService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** The access probe answers false, never an error, for what it cannot decide. */
class ApiChannelAccessControllerTest {

  private final ChannelAccessService access = mock(ChannelAccessService.class);
  private final ApiChannelAccessController controller = new ApiChannelAccessController(access);
  private final UUID viewer = UUID.randomUUID();
  private final UUID owner = UUID.randomUUID();

  private Jwt jwt(String subject) {
    return Jwt.withTokenValue("t").header("alg", "none").subject(subject).build();
  }

  @Test
  void anonymous_isFalseWithoutLookingAnyoneUp() {
    assertThat(controller.canAccess(owner.toString(), null).accessible()).isFalse();
    verifyNoInteractions(access);
  }

  @Test
  void malformedOwner_isFalse() {
    assertThat(controller.canAccess("not-a-uuid", jwt(viewer.toString())).accessible()).isFalse();
    verifyNoInteractions(access);
  }

  @Test
  void malformedSubject_isFalse() {
    assertThat(controller.canAccess(owner.toString(), jwt("nope")).accessible()).isFalse();
    verifyNoInteractions(access);
  }

  @Test
  void answersWhatTheServiceDecides() {
    when(access.canAccess(viewer, owner)).thenReturn(true);

    assertThat(controller.canAccess(owner.toString(), jwt(viewer.toString())).accessible()).isTrue();
    assertThat(controller.canAccess(UUID.randomUUID().toString(), jwt(viewer.toString())).accessible())
        .isFalse();
  }
}
