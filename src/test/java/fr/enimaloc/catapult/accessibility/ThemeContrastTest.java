package fr.enimaloc.catapult.accessibility;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ThemeContrastTest {

    static Stream<Arguments> colorPairs() throws IOException {
        String css;
        try (InputStream is = ThemeContrastTest.class.getResourceAsStream("/static/css/app.css")) {
            Objects.requireNonNull(is, "app.css not found on classpath");
            css = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<Arguments> params = new ArrayList<>();

        addPairs(params, "dark (default)", extractVarsFromBlock(css, ":root"));
        for (String theme : new String[]{"blanc", "old-steam", "nord", "dracula", "catppuccin", "tokyo-night"}) {
            addPairs(params, theme, extractVarsFromBlock(css, "[data-theme=\"" + theme + "\"]"));
        }
        return params.stream();
    }

    private static void addPairs(List<Arguments> params, String name, Map<String, String> vars) {
        String text = vars.get("--text");
        String bgBase = vars.get("--bg-base");
        String bgSurface = vars.get("--bg-surface");
        if (text != null && bgBase != null)
            params.add(Arguments.of(name + " | text on bg-base", text, bgBase));
        if (text != null && bgSurface != null)
            params.add(Arguments.of(name + " | text on bg-surface", text, bgSurface));
    }

    private static Map<String, String> extractVarsFromBlock(String css, String selector) {
        int start = css.indexOf(selector + " {");
        if (start == -1) start = css.indexOf(selector + "{");
        if (start == -1) return Map.of();
        int open = css.indexOf('{', start);
        int close = css.indexOf('}', open);
        String block = css.substring(open + 1, close);

        Map<String, String> vars = new HashMap<>();
        Matcher m = Pattern.compile("(--.+?):\\s*(#[0-9a-fA-F]{6})").matcher(block);
        while (m.find()) vars.put(m.group(1).trim(), m.group(2));
        return vars;
    }

    @Test
    void allThemesAndPairsArePresent() throws IOException {
        assertThat(colorPairs().toList()).hasSize(14);
    }

    @ParameterizedTest(name = "{0}: text={1} bg={2}")
    @MethodSource("colorPairs")
    void textOnBackground_meetsWcagAA(String pairName, String textHex, String bgHex) {
        double ratio = contrastRatio(textHex, bgHex);
        assertThat(ratio)
            .withFailMessage("WCAG AA failure [%s]: text=%s bg=%s ratio=%.2f (need ≥4.5)",
                pairName, textHex, bgHex, ratio)
            .isGreaterThanOrEqualTo(4.5);
    }

    private double contrastRatio(String hex1, String hex2) {
        double l1 = relativeLuminance(hex1);
        double l2 = relativeLuminance(hex2);
        return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
    }

    private double relativeLuminance(String hex) {
        int r = Integer.parseInt(hex.substring(1, 3), 16);
        int g = Integer.parseInt(hex.substring(3, 5), 16);
        int b = Integer.parseInt(hex.substring(5, 7), 16);
        return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b);
    }

    private double linearize(int channel) {
        double c = channel / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
