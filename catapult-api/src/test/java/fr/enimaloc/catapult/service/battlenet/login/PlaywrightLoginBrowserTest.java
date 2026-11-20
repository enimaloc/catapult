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

    /**
     * The optional "Verify your email" page as seen on 2026-11-20 (flow.battle.net/flow/<uuid>): the six code
     * slots of a login code, a "Continue" web component and a "Skip for now" link with a generated id.
     */
    private static final String VERIFY_EMAIL_PAGE = """
            <html><head><title>Verify your email</title></head><body>
            <h1>Verify your email</h1>
            <form id="code">
              <input id="slot_0" name="email_code_xs9ZRNF_0" type="text" maxlength="1">
              <input id="slot_1" name="email_code_xs9ZRNF_1" type="text" maxlength="1">
              <input id="slot_2" name="email_code_xs9ZRNF_2" type="text" maxlength="1">
              <input id="slot_3" name="email_code_xs9ZRNF_3" type="text" maxlength="1">
              <input id="slot_4" name="email_code_xs9ZRNF_4" type="text" maxlength="1">
              <input id="slot_5" name="email_code_xs9ZRNF_5" type="text" maxlength="1">
              <blz-button id="submit_btn" type="submit">Continue</blz-button>
            </form>
            <a id="link_link_1" href="#">Resend code</a>
            <a id="skip_link_MX478vo" href="#"
               onclick="window.skipped = true; document.body.innerHTML = ''; return false">Skip for now</a>
            """ + ROUTE_ANNOUNCER + """
            </body></html>
            """;

    @Test
    void theOptionalVerifyEmailPage_isSkipped_withoutAskingForACode() throws Exception {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch()) {
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
            page.setContent(VERIFY_EMAIL_PAGE);
            LoginPage login = PlaywrightLoginBrowser.onPage(context, page);

            assertThat(login.awaitOutcome(Duration.ofSeconds(2))).isNotInstanceOf(Outcome.CodeRequired.class);
            assertThat(page.evaluate("() => window.skipped")).isEqualTo(true);
            login.close();
        }
    }

    @Test
    void theOptionalVerifyEmailPage_isSkipped_whenItsSkipLinkRendersAfterTheSlots() throws Exception {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch()) {
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
            // The slots first, the links half a second later, as a page rendered in several passes
            String slotsFirst = VERIFY_EMAIL_PAGE
                    .replace("<a id=\"link_link_1\"", "<template id=\"late\"><a id=\"link_link_1\"")
                    .replace("return false\">Skip for now</a>", "return false\">Skip for now</a></template>"
                            + "<script>setTimeout(() => "
                            + "document.body.append(document.getElementById('late').content), 500)</script>");
            page.setContent(slotsFirst);
            LoginPage login = PlaywrightLoginBrowser.onPage(context, page);

            assertThat(login.awaitOutcome(Duration.ofSeconds(3))).isNotInstanceOf(Outcome.CodeRequired.class);
            assertThat(page.evaluate("() => window.skipped")).isEqualTo(true);
            login.close();
        }
    }

    @Test
    void theOptionalVerifyEmailPage_isSkipped_whenItsSkipLinkOnlyWorksOnceHydrated() throws Exception {
        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch()) {
            BrowserContext context = browser.newContext();
            Page page = context.newPage();
            // Server-rendered link shown at once, its click handler attached 800 ms later, as on 2026-11-20
            String lateHandler = VERIFY_EMAIL_PAGE
                    .replace("onclick=\"window.skipped = true; document.body.innerHTML = ''; return false\"", "")
                    .replace("</body>", "<script>setTimeout(() => document.querySelector('[id^=skip_link_]')"
                            + ".addEventListener('click', e => { e.preventDefault(); window.skipped = true;"
                            + " document.body.innerHTML = ''; }), 800)</script></body>");
            page.setContent(lateHandler);
            LoginPage login = PlaywrightLoginBrowser.onPage(context, page);

            assertThat(login.awaitOutcome(Duration.ofSeconds(5))).isNotInstanceOf(Outcome.CodeRequired.class);
            assertThat(page.evaluate("() => window.skipped")).isEqualTo(true);
            login.close();
        }
    }

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
