package fr.enimaloc.catapult.api.userapi;

import fr.enimaloc.catapult.getter.dtdd.DtddApiClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The public DTDD detail keeps its frozen topic stats: what DTDD adds to its own (the severity) stays out. */
class DtddDetailResponseTest {

    @Test
    void topicItemStats_keepTheirSevenFields_withoutTheSeverity() {
        DtddApiClient.DtddTopicItemStat stat = new DtddApiClient.DtddTopicItemStat(395866, 57, 3, 7, 153,
                "a dog dies", 10752, new DtddApiClient.DtddSeverity(
                        List.of("Implied", "Depicted", "Excessive"), List.of(2, 9, 1), List.of(17, 75, 8), 12, 2));

        DtddDetailResponse.DtddTopicItemStat[] stats =
                DtddDetailResponse.DtddTopicItemStat.of(new DtddApiClient.DtddTopicItemStat[]{stat});

        assertThat(stats).containsExactly(
                new DtddDetailResponse.DtddTopicItemStat(395866, 57, 3, 7, 153, "a dog dies", 10752));
        assertThat(JsonMapper.builder().build().writeValueAsString(stats[0])).isEqualTo(
                "{\"topicItemId\":395866,\"yesSum\":57,\"noSum\":3,\"numComments\":7,\"topicId\":153,"
                        + "\"topicName\":\"a dog dies\",\"itemId\":10752}");
    }

    @Test
    void noStats_staysNull() {
        assertThat(DtddDetailResponse.DtddTopicItemStat.of(null)).isNull();
    }
}
