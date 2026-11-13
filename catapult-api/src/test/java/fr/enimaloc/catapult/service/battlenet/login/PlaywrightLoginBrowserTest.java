package fr.enimaloc.catapult.service.battlenet.login;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaywrightLoginBrowserTest {

    private static final String TICKET = "EU-0123456789abcdef0123456789ABCDEF-123456";

    @Test
    void extractsTicketFromLocalhostRedirect() {
        assertThat(PlaywrightLoginBrowser.extractTicket("http://localhost:0/?ST=" + TICKET)).isEqualTo(TICKET);
        assertThat(PlaywrightLoginBrowser.extractTicket("http://localhost:0/?a=b&ST=" + TICKET)).isEqualTo(TICKET);
    }

    @Test
    void ignoresNonLocalhostUrls() {
        assertThat(PlaywrightLoginBrowser.extractTicket("https://eu.account.battle.net/?ST=" + TICKET)).isNull();
        assertThat(PlaywrightLoginBrowser.extractTicket("http://localhost:0/?ST=nope")).isNull();
    }
}
