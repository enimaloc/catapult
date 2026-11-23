package fr.enimaloc.catapult.simulation.external;

import fr.enimaloc.catapult.service.enrichment.chess.ChessNotFoundException;
import fr.enimaloc.catapult.service.enrichment.chess.ChessUnavailableException;
import fr.enimaloc.catapult.service.enrichment.chess.ChesscomDailyGame;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimulatedChessClientTest {

    private final SimulatedChessClient client = new SimulatedChessClient();

    @Test
    void anyNameIsAPlayerAnswerExpectedShapes() {
        assertThat(client.chesscomPlayer("SimPlayer").username()).isEqualTo("simplayer");
        assertThat(client.chesscomPlayer("SimPlayer").playerId())
                .isEqualTo(client.chesscomPlayer("simplayer").playerId());
        assertThat(client.chesscomStats("sim").modes()).containsKeys("rapid", "blitz", "daily");
        assertThat(client.chesscomGames("sim")).hasSize(2).filteredOn(ChesscomDailyGame::yourTurn).hasSize(1);
        assertThat(client.lichessUser("SimPlayer").id()).isEqualTo("simplayer");
        assertThat(client.lichessUser("SimPlayer").username()).isEqualTo("SimPlayer");
        assertThat(client.lichessUser("SimPlayer").playingUrl()).isNotNull();
    }

    @Test
    void goneIsNotFoundAndDownIsAnOutage() {
        assertThatThrownBy(() -> client.chesscomPlayer("gone")).isInstanceOf(ChessNotFoundException.class);
        assertThatThrownBy(() -> client.lichessUser("Gone")).isInstanceOf(ChessNotFoundException.class);
        assertThatThrownBy(() -> client.chesscomStats("down")).isInstanceOf(ChessUnavailableException.class);
        assertThatThrownBy(() -> client.lichessUser("down")).isInstanceOf(ChessUnavailableException.class);
    }
}
