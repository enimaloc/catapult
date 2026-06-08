package fr.enimaloc.catapult.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatisticsServiceTest {

    private final StatisticsService stats = new StatisticsService();

    @Test
    void zTest_returnsSignificant_whenClearDifference() {
        // 80/100 vs 20/100 — clear difference
        StatisticsService.ZTestResult result = stats.twoProportionZTest(80, 100, 20, 100);
        assertThat(result.pValue()).isLessThan(0.05);
        assertThat(result.significant()).isTrue();
    }

    @Test
    void zTest_returnsNotSignificant_whenSimilarRates() {
        // 51/100 vs 49/100 — essentially the same
        StatisticsService.ZTestResult result = stats.twoProportionZTest(51, 100, 49, 100);
        assertThat(result.pValue()).isGreaterThanOrEqualTo(0.05);
        assertThat(result.significant()).isFalse();
    }

    @Test
    void zTest_returnsInsufficientData_whenSampleTooSmall() {
        StatisticsService.ZTestResult result = stats.twoProportionZTest(5, 10, 3, 10);
        assertThat(result.insufficientData()).isTrue();
    }

    @Test
    void npsScore_calculatesCorrectly() {
        // promoters (9-10): 3, detractors (0-6): 1, passives (7-8): 1
        // NPS = (3/5 - 1/5) * 100 = 40
        int[] scores = {10, 9, 9, 7, 3};
        assertThat(stats.npsScore(scores)).isEqualTo(40);
    }

    @Test
    void npsScore_returnsZero_whenEmpty() {
        assertThat(stats.npsScore(new int[0])).isEqualTo(0);
    }

    @Test
    void npsScore_returnsNegative_whenMostlyDetractors() {
        int[] scores = {0, 1, 2, 3, 10};
        // promoters: 1, detractors: 4 → NPS = (1/5 - 4/5) * 100 = -60
        assertThat(stats.npsScore(scores)).isEqualTo(-60);
    }
}
