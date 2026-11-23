package fr.enimaloc.catapult.service.enrichment.publisher;

import fr.enimaloc.catapult.domain.binding.GameBinding.SourceType;
import fr.enimaloc.catapult.getter.DetectedGame;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PublisherGamesChessTest {

    @Test
    void theChessSitesIgdbIdsAreChess() {
        DetectedGame manual = new DetectedGame("x", SourceType.MANUAL, "Something");
        assertThat(PublisherGames.chess(manual, "121957")).isTrue();
        assertThat(PublisherGames.chess(manual, "122008")).isTrue();
        assertThat(PublisherGames.chess(manual, "123")).isFalse();
        assertThat(PublisherGames.chess(manual, null)).isFalse();
    }

    @Test
    void theTwitchChessCategoryIsChessByItsName() {
        assertThat(PublisherGames.chess(new DetectedGame(null, SourceType.MANUAL, "Chess"), null)).isTrue();
        assertThat(PublisherGames.chess(new DetectedGame(null, SourceType.MANUAL, " chess "), null)).isTrue();
        assertThat(PublisherGames.chess(new DetectedGame(null, SourceType.MANUAL, "Chess.com"), null)).isTrue();
        assertThat(PublisherGames.chess(new DetectedGame(null, SourceType.MANUAL, "Lichess"), null)).isTrue();
        assertThat(PublisherGames.chess(new DetectedGame(null, SourceType.MANUAL, "Chess Ultra"), null)).isFalse();
        assertThat(PublisherGames.chess(new DetectedGame(null, SourceType.MANUAL, null), null)).isFalse();
        assertThat(PublisherGames.chess(null, null)).isFalse();
    }
}
