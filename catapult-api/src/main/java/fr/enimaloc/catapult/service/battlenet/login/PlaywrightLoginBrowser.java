package fr.enimaloc.catapult.service.battlenet.login;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.WaitForSelectorState;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link BattleNetLoginBrowser} backed by headless Chromium through Playwright (spec §9). Playwright and the
 * browser are created lazily on the first {@link #open}, so the application starts on machines without
 * Chromium. A fresh {@link BrowserContext} is opened per login.
 * <p>
 * Thread-safety: the coordinator drives this bean from a single thread; the lazy creation is synchronized but
 * the pages themselves are not meant to be shared between threads.
 */
@Slf4j
@Component
@ConditionalOnBooleanProperty("battlenet.enabled")
public class PlaywrightLoginBrowser implements BattleNetLoginBrowser {

    static final String LOGIN_URL = "https://eu.account.battle.net/login/?externalChallenge=login&app=app";

    private static final String ACCOUNT_NAME = "input#accountName:visible";
    private static final String PASSWORD = "input#password:visible";
    private static final String SUBMIT = "#submit";
    /** A visible error element on the login form after a submit: Battle.net's explicit rejection. */
    private static final String LOGIN_ERROR = ".alert-error:visible, .form-error:visible, [role=alert]:visible";
    // Verified against the live site on 2026-11-14: after the password, a fresh context lands on a "Security
    // Check" page (/challenge/<uuid>/choose, a method <select> and #submit), then on the code page
    // (/challenge/<uuid>) whose one-time-code input submits the form by itself once filled.
    private static final String CODE_INPUT = "input[autocomplete=one-time-code]:visible";
    private static final String SUBMIT_VISIBLE = "#submit:visible";
    /** Last resort when the code input lacks its autocomplete hint: any other visible field. */
    private static final String CODE_INPUT_FALLBACK =
            "input:visible:not(#accountName):not(#password):not([type=hidden])";
    /** Visible controls and alerts, without any value, for the diagnostic of an unrecognised page. */
    private static final String VISIBLE_ELEMENTS_SCRIPT = """
            () => Array.from(document.querySelectorAll('input, select, textarea, button, [role=alert]'))
                .filter(e => e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden')
                .filter(e => !(e.tagName === 'INPUT' && e.type === 'hidden'))
                .slice(0, 40)
                .map(e => ({
                    tag: e.tagName.toLowerCase(),
                    id: e.id || null,
                    name: e.getAttribute('name'),
                    type: e.getAttribute('type'),
                    role: e.getAttribute('role'),
                    text: (e.tagName === 'BUTTON' || e.getAttribute('role') === 'alert')
                        ? (e.innerText || '').slice(0, 200) : null
                }))
            """;

    private static final Pattern TICKET = Pattern.compile("[?&]ST=(EU-[0-9a-fA-F]{32}-\\d+)");
    private static final double ACTION_TIMEOUT_MS = 15_000;
    private static final long POLL_MS = 250;
    private static final double QUICK_CLICK_TIMEOUT_MS = 3_000;
    private static final long CODE_AUTO_SUBMIT_MS = 1_000;
    private static final double CODE_SUBMITTED_TIMEOUT_MS = 10_000;

    private @Nullable Playwright playwright;
    private @Nullable Browser browser;

    /** The ticket carried by a {@code localhost} redirect URL, or null for any other URL. */
    static @Nullable String extractTicket(String url) {
        if (!url.contains("localhost")) return null;
        Matcher m = TICKET.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public LoginPage open(@Nullable String storageState) {
        Browser b = browser();
        Browser.NewContextOptions options = new Browser.NewContextOptions();
        if (storageState != null) options.setStorageState(storageState);
        BrowserContext context = b.newContext(options);
        try {
            Page page = context.newPage();
            page.setDefaultTimeout(ACTION_TIMEOUT_MS);
            AtomicReference<String> ticket = new AtomicReference<>();
            page.onRequest(request -> {
                String t = extractTicket(request.url());
                if (t != null) ticket.compareAndSet(null, t);
            });
            page.navigate(LOGIN_URL);
            return new PlaywrightLoginPage(context, page, ticket);
        } catch (RuntimeException e) {
            context.close();
            throw e;
        }
    }

    private synchronized Browser browser() {
        if (browser == null || !browser.isConnected()) {
            if (playwright == null) playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        }
        return browser;
    }

    @PreDestroy
    synchronized void shutdown() {
        try {
            if (browser != null) browser.close();
            if (playwright != null) playwright.close();
        } catch (RuntimeException e) {
            log.warn("Closing the Battle.net login browser failed ({})", e.getClass().getSimpleName());
        } finally {
            browser = null;
            playwright = null;
        }
    }

    private static final class PlaywrightLoginPage implements LoginPage {

        private final BrowserContext context;
        private final Page page;
        private final AtomicReference<String> ticket;
        /** Path of the security check page already submitted, so it is clicked only once. */
        private @Nullable String chooseSubmittedFor;

        PlaywrightLoginPage(BrowserContext context, Page page, AtomicReference<String> ticket) {
            this.context = context;
            this.page = page;
            this.ticket = ticket;
        }

        @Override
        public void submitCredentials(String email, String password) {
            Locator account = page.locator(ACCOUNT_NAME);
            Locator pwd = page.locator(PASSWORD);
            // Either the e-mail form or, with a remembered account, directly the password form shows up.
            account.or(pwd).first().waitFor();
            if (account.count() > 0) {
                account.fill(email);
                page.click(SUBMIT);
                pwd.waitFor();
            }
            pwd.fill(password);
            page.click(SUBMIT);
        }

        @Override
        public Outcome awaitOutcome(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            do {
                String t = ticket.get();
                if (t != null) return new Outcome.Ticket(t);
                String url = page.url();
                LoginPageInspection.Step step = LoginPageInspection.classify(url);
                if (step == LoginPageInspection.Step.CHALLENGE_CHOOSE) {
                    // Keep the default method (e-mail) and ask for the code, once per challenge.
                    String path = LoginPageInspection.withoutQuery(url);
                    if (!path.equals(chooseSubmittedFor)) {
                        chooseSubmittedFor = path;
                        log.debug("Battle.net security check: requesting a code with the default method");
                        clickIfVisible(SUBMIT_VISIBLE);
                    }
                } else if (step == LoginPageInspection.Step.CHALLENGE_CODE || page.locator(CODE_INPUT).count() > 0
                        || (page.locator(CODE_INPUT_FALLBACK).count() > 0 && page.locator(PASSWORD).count() == 0
                        && page.locator(ACCOUNT_NAME).count() == 0)) {
                    return new Outcome.CodeRequired();
                } else if (page.locator(LOGIN_ERROR).count() > 0) {
                    // Fixed text: the page's own message could echo the account e-mail.
                    return new Outcome.Failed("login rejected");
                }
                page.waitForTimeout(POLL_MS);
            } while (System.nanoTime() < deadline);
            String t = ticket.get();
            if (t != null) return new Outcome.Ticket(t);
            logUnrecognisedPage();
            return new Outcome.TimedOut();
        }

        @Override
        public void submitCode(String code) {
            Locator otp = page.locator(CODE_INPUT);
            Locator input = (otp.count() > 0 ? otp : page.locator(CODE_INPUT_FALLBACK)).first();
            input.fill(code);
            // The code input submits the form by itself: #submit is often already gone.
            page.waitForTimeout(CODE_AUTO_SUBMIT_MS);
            clickIfVisible(SUBMIT_VISIBLE);
            // Leave the code page before awaitOutcome, or it would ask for a code again straight away.
            try {
                input.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN)
                        .setTimeout(CODE_SUBMITTED_TIMEOUT_MS));
            } catch (PlaywrightException e) {
                log.debug("The Battle.net code page is still shown after submitting the code");
            }
        }

        /** Clicks the first match if visible; a race with a navigation is not an error. */
        private void clickIfVisible(String selector) {
            Locator target = page.locator(selector);
            if (target.count() == 0) return;
            try {
                target.first().click(new Locator.ClickOptions().setTimeout(QUICK_CLICK_TIMEOUT_MS));
            } catch (PlaywrightException e) {
                log.debug("Click on a Battle.net login button failed ({})", e.getClass().getSimpleName());
            }
        }

        /** Logs what the page shows when nothing was recognised, so a new Blizzard page shows up in the logs. */
        private void logUnrecognisedPage() {
            try {
                List<LoginPageInspection.PageElement> elements = new ArrayList<>();
                if (page.evaluate(VISIBLE_ELEMENTS_SCRIPT) instanceof List<?> raw) {
                    for (Object o : raw) {
                        if (o instanceof Map<?, ?> m) {
                            elements.add(new LoginPageInspection.PageElement(String.valueOf(m.get("tag")),
                                    str(m.get("id")), str(m.get("name")), str(m.get("type")), str(m.get("role")),
                                    str(m.get("text"))));
                        }
                    }
                }
                log.warn("Battle.net login page not recognised before the timeout: {}",
                        LoginPageInspection.describe(page.url(), page.title(), elements));
            } catch (RuntimeException e) {
                log.warn("Battle.net login page not recognised before the timeout (diagnostic failed: {})",
                        e.getClass().getSimpleName());
            }
        }

        private static @Nullable String str(@Nullable Object o) {
            return o == null ? null : o.toString();
        }

        @Override
        public String storageState() {
            return context.storageState();
        }

        @Override
        public void close() {
            context.close();
        }
    }
}
