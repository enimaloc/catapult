package fr.enimaloc.catapult.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import proto.ExternalGame;
import proto.ExternalGameSource;
import proto.Game;

import java.util.List;

@Slf4j
@Component
@Primary
@ConditionalOnProperty(name = "app.mock.igdb", havingValue = "true")
public class MockIgdbClient extends IgdbClient {

    @Override
    public List<ExternalGameSource> findSourcesByName(String name, String token) {
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

}
