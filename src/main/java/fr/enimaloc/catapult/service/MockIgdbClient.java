package fr.enimaloc.catapult.service;

import com.api.igdb.exceptions.RequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import proto.ExternalGame;
import proto.ExternalGameSource;
import proto.Game;

import java.util.List;

@Slf4j
@Component
@Primary
@Profile("mock-web")
public class MockIgdbClient extends IgdbClient {

    @Override
    public List<ExternalGameSource> findSourcesByName(String name, String token) throws RequestException {
        log.debug("[Mock IGDB] findSourcesByName({}) — returning empty", name);
        return List.of();
    }

    @Override
    public List<ExternalGame> findExternalGameByUid(String uid, long sourceId, String token) {
        return List.of();
    }

    @Override
    public List<ExternalGame> findExternalGamesByUids(List<String> uids, long sourceId, String token) {
        return List.of();
    }

    @Override
    public List<Game> searchByName(String name, String token) {
        return List.of();
    }

    @Override
    public List<Game> fetchGamesByIds(List<String> igdbIds, String fields, String token) {
        return List.of();
    }

    @Override
    public List<Game> fetchGameById(String igdbId, String fields, String token) {
        return List.of();
    }

    @Override
    public List<Game> fetchGamePage(int limit, int offset, String token) {
        return List.of();
    }
}
