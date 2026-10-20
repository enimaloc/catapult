package fr.enimaloc.catapult.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ObsDockTest {

    private static final String OBS = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/127.0.0.0 Safari/537.36 OBS/31.0.2";
    private static final String BROWSER = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/127.0.0.0 Safari/537.36";

    /** OBS gets the dock unless view=full asks otherwise; elsewhere only view=dock gives it. */
    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "obs,     null, true",
            "obs,     dock, true",
            "obs,     DOCK, true",
            "obs,     xyz,  true",
            "obs,     full, false",
            "obs,     Full, false",
            "browser, null, false",
            "browser, full, false",
            "browser, xyz,  false",
            "browser, dock, true",
            "null,    null, false",
            "null,    dock, true",
    })
    void wanted(String agent, String view, boolean dock) {
        String userAgent = switch (String.valueOf(agent)) {
            case "obs" -> OBS;
            case "browser" -> BROWSER;
            default -> null;
        };
        assertThat(ObsDock.wanted(userAgent, view)).isEqualTo(dock);
    }
}
