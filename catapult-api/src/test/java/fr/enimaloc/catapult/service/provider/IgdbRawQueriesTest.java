package fr.enimaloc.catapult.service.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import fr.enimaloc.catapult.common.dto.admin.AdminProviderIgdbQueryRequest;
import fr.enimaloc.catapult.service.igdb.IgdbService;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IgdbRawQueriesTest {

    private static final String EXPECTED =
            "IGDB token not available: check the IGDB client-id and secret configuration";

    @Test
    void blankToken_explainsInEnglish_inBothAnswers() {
        IgdbService igdbService = mock(IgdbService.class);
        when(igdbService.getAppToken()).thenReturn("");
        RestClient restClient = mock(RestClient.class);
        IgdbRawQueries queries = new IgdbRawQueries(
                igdbService, restClient, mock(RawProviderResponseSupport.class), mock(ObjectMapper.class));
        var request = new AdminProviderIgdbQueryRequest("games", "fields name;");

        var raw = queries.query(request);
        var explorer = queries.explorerQuery(request);

        assertThat(raw.status()).isEqualTo(502);
        assertThat(raw.error()).isEqualTo(EXPECTED);
        assertThat(explorer.error()).isEqualTo(EXPECTED);
        verifyNoInteractions(restClient);
    }
}
