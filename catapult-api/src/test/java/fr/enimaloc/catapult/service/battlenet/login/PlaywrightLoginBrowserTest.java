package fr.enimaloc.catapult.service.battlenet.login;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginBrowser.LoginPage;
import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginBrowser.Outcome;
import org.junit.jupiter.api.Test;

import java.time.Duration;

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

    /**
     * The e-mail code page as seen on 2026-11-20 (flow.battle.net/flow/<uuid>, "Verify your email"): six
     * one-character inputs named email_code_<id>_<n>, and an empty error placeholder. Filling the last slot
     * submits, as the real page does.
     */
    private static final String EMAIL_CODE_PAGE = """
            <html><head><title>Verify your email</title></head><body>
            <form id="code">
              <div class="form-error" style="min-height: 1em"></div>
              <input id="slot_0" name="email_code_gJipyVc_0" type="text" maxlength="1">
              <input id="slot_1" name="email_code_gJipyVc_1" type="text" maxlength="1">
              <input id="slot_2" name="email_code_gJipyVc_2" type="text" maxlength="1">
              <input id="slot_3" name="email_code_gJipyVc_3" type="text" maxlength="1">
              <input id="slot_4" name="email_code_gJipyVc_4" type="text" maxlength="1">
              <input id="slot_5" name="email_code_gJipyVc_5" type="text" maxlength="1">
            </form>
            <script>
              document.getElementById('slot_5').addEventListener('input', () => {
                window.submitted = [...document.querySelectorAll('#code input')].map(i => i.value).join('');
                document.getElementById('code').remove();
              });
            </script>
            </body></html>
            """;

    @Test
    void theEmailCodePageWithSixSlots_asksForTheCode_andTakesItOneDigitPerSlot() throws Exception {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch()) {
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
            page.setContent(EMAIL_CODE_PAGE);
            LoginPage login = PlaywrightLoginBrowser.onPage(context, page);

            assertThat(login.awaitOutcome(Duration.ofSeconds(2))).isInstanceOf(Outcome.CodeRequired.class);

            login.submitCode("123456");
            assertThat(page.evaluate("() => window.submitted")).isEqualTo("123456");
            login.close();
        }
    }

    /** Next.js's route announcer: an empty, 1-pixel live region every flow.battle.net page carries. */
    private static final String ROUTE_ANNOUNCER = """
            <div id="__next-route-announcer__" role="alert" aria-live="assertive" style="position: absolute; \
            border: 0; height: 1px; margin: -1px; padding: 0; width: 1px; clip: rect(0 0 0 0); overflow: hidden; \
            white-space: nowrap"></div>""";

    @Test
    void nextJsRouteAnnouncer_isNotReadAsARejection() throws Exception {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch()) {
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
            page.setContent("<html><body><p>Loading</p>" + ROUTE_ANNOUNCER + "</body></html>");
            LoginPage login = PlaywrightLoginBrowser.onPage(context, page);

            assertThat(login.awaitOutcome(Duration.ofSeconds(1))).isInstanceOf(Outcome.TimedOut.class);
            login.close();
        }
    }
}
