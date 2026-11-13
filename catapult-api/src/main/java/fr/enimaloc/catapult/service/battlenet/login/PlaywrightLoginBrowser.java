package fr.enimaloc.catapult.service.battlenet.login;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
    // UNVERIFIED: the real 2FA page's markup was never captured. These generic selectors were validated only by
    // the spike: any visible input besides the account name and password is taken for the code field.
    private static final String CODE_INPUT = "input:visible:not(#accountName):not(#password):not([type=hidden])";
    private static final String CODE_SUBMIT = "button[type=submit]:visible, #submit:visible, input[type=submit]:visible";

    private static final Pattern TICKET = Pattern.compile("[?&]ST=(EU-[0-9a-fA-F]{32}-\\d+)");
    private static final double ACTION_TIMEOUT_MS = 15_000;
    private static final long POLL_MS = 250;

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
                if (page.locator(CODE_INPUT).count() > 0 && page.locator(PASSWORD).count() == 0
                        && page.locator(ACCOUNT_NAME).count() == 0) {
                    return new Outcome.CodeRequired();
                }
                if (page.locator(LOGIN_ERROR).count() > 0) {
                    // Fixed text: the page's own message could echo the account e-mail.
                    return new Outcome.Failed("login rejected");
                }
                page.waitForTimeout(POLL_MS);
            } while (System.nanoTime() < deadline);
            String t = ticket.get();
            return t != null ? new Outcome.Ticket(t) : new Outcome.TimedOut();
        }

        @Override
        public void submitCode(String code) {
            page.locator(CODE_INPUT).first().fill(code);
            page.locator(CODE_SUBMIT).first().click();
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
