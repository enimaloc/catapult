package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The public DTDD detail's topic stats: DTDD's own, with the topic's severity as DTDD gives it. */
class DtddDetailResponseTest {

    private static DtddApiClient.DtddTopicItemStat stat(DtddApiClient.DtddSeverity severity) {
        return new DtddApiClient.DtddTopicItemStat(395866, 57, 3, 7, 153, "a dog dies", 10752, severity);
    }

    @Test
    void topicItemStats_carryTheSeverity() {
        DtddApiClient.DtddSeverity severity = new DtddApiClient.DtddSeverity(
                List.of("Implied", "Depicted", "Excessive"), List.of(2, 9, 1), List.of(17, 75, 8), 12, 2);

        DtddDetailResponse.DtddTopicItemStat[] stats =
                DtddDetailResponse.DtddTopicItemStat.of(new DtddApiClient.DtddTopicItemStat[]{stat(severity)});

        assertThat(JsonMapper.builder().build().writeValueAsString(stats[0])).isEqualTo(
                "{\"topicItemId\":395866,\"yesSum\":57,\"noSum\":3,\"numComments\":7,\"topicId\":153,"
                        + "\"topicName\":\"a dog dies\",\"itemId\":10752,\"severity\":{\"levels\":[\"Implied\","
                        + "\"Depicted\",\"Excessive\"],\"counts\":[2,9,1],\"percents\":[17,75,8],\"total\":12,"
                        + "\"verified\":2}}");
    }

    @Test
    void aTopicWithoutLevels_hasNoSeverity() {
        DtddDetailResponse.DtddTopicItemStat[] stats =
                DtddDetailResponse.DtddTopicItemStat.of(new DtddApiClient.DtddTopicItemStat[]{stat(null)});

        assertThat(stats[0].severity()).isNull();
    }

    @Test
    void noStats_staysNull() {
        assertThat(DtddDetailResponse.DtddTopicItemStat.of(null)).isNull();
    }
}
