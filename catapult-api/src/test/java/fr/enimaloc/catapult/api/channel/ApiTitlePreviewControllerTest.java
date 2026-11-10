package fr.enimaloc.catapult.api.channel;

import fr.enimaloc.catapult.api.ApiUserResolver;
import fr.enimaloc.catapult.api.error.ApiProblem;
import fr.enimaloc.catapult.common.dto.channel.TitlePreviewDto;
import fr.enimaloc.catapult.common.dto.channel.TitlePreviewRequest;
import fr.enimaloc.catapult.domain.account.UserAccount;
import fr.enimaloc.catapult.service.title.StreamTitleService;
import fr.enimaloc.catapult.service.title.TitlePreviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiTitlePreviewControllerTest {

    private final ApiUserResolver users = mock(ApiUserResolver.class);
    private final StreamTitleService titles = mock(StreamTitleService.class);
    private final TitlePreviewService previews = mock(TitlePreviewService.class);
    private final ApiTitlePreviewController controller = new ApiTitlePreviewController(users, titles, previews);
    private final UserAccount channel = new UserAccount();
    private final TitlePreviewRequest request = new TitlePreviewRequest("Live {{GAME}}", "TEMPLATE", null, null);

    @BeforeEach
    void setUp() {
        channel.setId(UUID.randomUUID());
        when(users.user(channel.getId())).thenReturn(channel);
    }

    @Test
    void preview_answersTheRenderedTitle_orNullWhenNoneWouldBeSent() {
        when(titles.previewAvailableFor(channel)).thenReturn(true);
        when(previews.preview(channel, request)).thenReturn(Optional.of("Live Doom"));
        assertThat(controller.preview(channel.getId(), request)).isEqualTo(new TitlePreviewDto("Live Doom"));

        when(previews.preview(channel, request)).thenReturn(Optional.empty());
        assertThat(controller.preview(channel.getId(), request)).isEqualTo(new TitlePreviewDto(null));
    }

    @Test
    void preview_gated() {
        when(titles.previewAvailableFor(channel)).thenReturn(false);

        assertThatThrownBy(() -> controller.preview(channel.getId(), request)).isInstanceOf(ApiProblem.class);
        verify(previews, never()).preview(any(), any());
    }
}
