package fr.enimaloc.catapult.controller;

import org.junit.jupiter.api.Test;
import org.springframework.boot.info.BuildProperties;

import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class BuildVersionAdviceTest {

    @Test
    void tellsTheBuildsVersion() {
        Properties info = new Properties();
        info.setProperty("version", "0.3.7-BETA");

        assertThat(new BuildVersionAdvice(Optional.of(new BuildProperties(info))).appVersion()).isEqualTo("0.3.7-BETA");
    }

    @Test
    void tellsNone_withoutBuildInfo() {
        assertThat(new BuildVersionAdvice(Optional.empty()).appVersion()).isNull();
    }
}
