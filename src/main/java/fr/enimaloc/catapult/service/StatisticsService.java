package fr.enimaloc.catapult.service;

import org.springframework.stereotype.Service;

@Service
public class StatisticsService {

    private static final int MIN_SAMPLE_SIZE = 30;

    public record ZTestResult(double pValue, boolean significant, boolean insufficientData) {}

    public ZTestResult twoProportionZTest(long successA, long totalA, long successB, long totalB) {
        if (totalA < MIN_SAMPLE_SIZE || totalB < MIN_SAMPLE_SIZE) {
            return new ZTestResult(Double.NaN, false, true);
        }
        double p1 = (double) successA / totalA;
        double p2 = (double) successB / totalB;
        double pPooled = (double) (successA + successB) / (totalA + totalB);
        double se = Math.sqrt(pPooled * (1 - pPooled) * (1.0 / totalA + 1.0 / totalB));
        if (se == 0) return new ZTestResult(1.0, false, false);
        double z = Math.abs((p1 - p2) / se);
        double pValue = 2 * (1 - normalCdf(z));
        return new ZTestResult(pValue, pValue < 0.05, false);
    }

    public int npsScore(int[] scores) {
        if (scores.length == 0) return 0;
        long promoters  = 0, detractors = 0;
        for (int s : scores) {
            if (s >= 9) promoters++;
            else if (s <= 6) detractors++;
        }
        return (int) Math.round((double)(promoters - detractors) / scores.length * 100);
    }

    // Abramowitz & Stegun approximation for the standard normal CDF
    private double normalCdf(double z) {
        double t = 1.0 / (1.0 + 0.2316419 * z);
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        return 1.0 - (1.0 / Math.sqrt(2 * Math.PI)) * Math.exp(-0.5 * z * z) * poly;
    }
}
