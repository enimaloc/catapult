package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.repository.IgdbRatingDescriptorRepository;
import fr.enimaloc.catapult.repository.TwitchCclDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AdminCclServiceTest {

    @Test
    void constructor_setsFields() {
        RestClient restClient = mock(RestClient.class);
        TwitchCclDefinitionRepository twitchRepo = mock(TwitchCclDefinitionRepository.class);
        IgdbRatingDescriptorRepository igdbRepo = mock(IgdbRatingDescriptorRepository.class);

        AdminCclService service = new AdminCclService(restClient, twitchRepo, igdbRepo, null);

        assertThat(service).isNotNull();
    }

    @Test
    void getAllCcls_delegatesToRepository() {
        RestClient restClient = mock(RestClient.class);
        TwitchCclDefinitionRepository twitchRepo = mock(TwitchCclDefinitionRepository.class);
        IgdbRatingDescriptorRepository igdbRepo = mock(IgdbRatingDescriptorRepository.class);
        AdminCclService service = new AdminCclService(restClient, twitchRepo, igdbRepo, null);

        when(twitchRepo.findAll()).thenReturn(List.of());

        assertThat(service.getAllCcls()).isEmpty();
    }
}
