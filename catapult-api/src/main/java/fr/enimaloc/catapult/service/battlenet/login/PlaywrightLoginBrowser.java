package fr.enimaloc.catapult.service.battlenet.login;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitForSelectorState;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
    // Seen on 2026-11-20: the e-mail code page moved to flow.battle.net/flow/<uuid> ("Verify your email") with one
    // input per digit (#slot_0..5, named email_code_<id>_<n>), and an error placeholder the rejection check matches.
    private static final String CODE_SLOTS = "input[name^=email_code_]:visible";
    private static final String CODE_INPUT = "input[autocomplete=one-time-code]:visible, " + CODE_SLOTS;
    private static final String SUBMIT_VISIBLE = "#submit:visible";
    /** Fill target when the code input lacks its autocomplete hint; never used to recognise a page. */
    private static final String CODE_INPUT_FALLBACK =
            "input:visible:not(#accountName):not(#password):not([type=hidden])";
    /**
     * Visible controls, links and alerts, without any value, for the diagnostic of an unrecognised page. Looks
     * inside open shadow roots, and lists custom elements with a short text: the "Verify your email" page's
     * "Continue" and "Skip for now" never showed up as buttons.
     */
    private static final String VISIBLE_ELEMENTS_SCRIPT = """
            () => {
                const controls = 'input, select, textarea, button, a[href], [role=button], [role=link], '
                    + '[role=alert], .alert-error, .form-error';
                const found = [];
                const walk = root => {
                    for (const e of root.querySelectorAll('*')) {
                        const custom = e.tagName.includes('-') && (e.innerText || '').trim().length > 0
                            && (e.innerText || '').trim().length <= 40;
                        if (e.matches(controls) || custom) found.push(e);
                        if (e.shadowRoot) walk(e.shadowRoot);
                    }
                };
                walk(document);
                return found
                    .filter(e => e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden')
                    .filter(e => !(e.tagName === 'INPUT' && e.type === 'hidden'))
                    .slice(0, 60)
                    .map(e => ({
                        tag: e.tagName.toLowerCase(),
                        id: e.id || null,
                        name: e.getAttribute('name'),
                        type: e.getAttribute('type'),
                        role: e.getAttribute('role'),
                        text: ['INPUT', 'SELECT', 'TEXTAREA'].includes(e.tagName)
                            ? null : (e.innerText || '').slice(0, 2000)
                    }));
            }
            """;

    private static final Pattern TICKET = Pattern.compile("[?&]ST=(EU-[0-9a-fA-F]{32}-\\d+)");
    private static final double ACTION_TIMEOUT_MS = 15_000;
    private static final long POLL_MS = 250;
    private static final double QUICK_CLICK_TIMEOUT_MS = 3_000;
    private static final long CODE_AUTO_SUBMIT_MS = 1_000;
    private static final double CODE_SUBMITTED_TIMEOUT_MS = 10_000;
    /** Requests worth logging when a code is not accepted: the page's calls, not its assets. */
    private static final Set<String> EXCHANGE_TYPES = Set.of("document", "xhr", "fetch");

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

    /** The login logic on an already open page, for tests on a local copy of a Battle.net page. */
    static LoginPage onPage(BrowserContext context, Page page) {
        return new PlaywrightLoginPage(context, page, new AtomicReference<>());
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
                    // Recorded only once the click is sent: the URL changes before the button is rendered.
                    if (!path.equals(chooseSubmittedFor) && clickIfVisible(SUBMIT_VISIBLE)) {
                        chooseSubmittedFor = path;
                        log.debug("Battle.net security check: requested a code with the default method");
                    }
                } else if (step == LoginPageInspection.Step.CHALLENGE_CODE || page.locator(CODE_INPUT).count() > 0) {
                    // A code already typed and not rejected is still being submitted: keep waiting for the ticket.
                    if (awaitsCode()) {
                        // The page's controls, so a skippable page asking for a code shows up in the logs
                        if (page.locator(CODE_SLOTS).count() > 1) logPage("Battle.net asks for an e-mail code");
                        return new Outcome.CodeRequired();
                    }
                } else if (page.locator(LOGIN_ERROR).count() > 0) {
                    logPage("Battle.net login page read as a rejection");
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
            // What the page sends once the code is in, for the diagnostic when it stays on the code page
            List<Response> responses = Collections.synchronizedList(new ArrayList<>());
            Consumer<Response> onResponse = r -> {
                if (EXCHANGE_TYPES.contains(r.request().resourceType())) responses.add(r);
            };
            page.onResponse(onResponse);
            try {
                typeCode(code, responses);
            } finally {
                page.offResponse(onResponse);
            }
        }

        private void typeCode(String code, List<Response> responses) {
            Locator slots = page.locator(CODE_SLOTS);
            Locator otp = page.locator(CODE_INPUT);
            Locator input = (otp.count() > 0 ? otp : page.locator(CODE_INPUT_FALLBACK)).first();
            int slotCount = slots.count();
            if (slotCount > 1) {
                // One digit per input, in order: the page submits once the last one is filled
                String digits = code.replaceAll("\\s+", "");
                for (int i = 0; i < Math.min(slotCount, digits.length()); i++) {
                    slots.nth(i).fill(String.valueOf(digits.charAt(i)));
                }
            } else {
                input.fill(code);
            }
            // The code input submits the form by itself: #submit is often already gone.
            page.waitForTimeout(CODE_AUTO_SUBMIT_MS);
            clickIfVisible(SUBMIT_VISIBLE);
            // Leave the code page before awaitOutcome, or it would ask for a code again straight away.
            try {
                input.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN)
                        .setTimeout(CODE_SUBMITTED_TIMEOUT_MS));
            } catch (PlaywrightException e) {
                // How many slots hold something, never what: tells a fill the page ignored from a refused code
                Object filled = slotCount > 1 ? page.evaluate("() => [...document.querySelectorAll("
                        + "'input[name^=email_code_]')].filter(i => i.value !== '').length") : "n/a";
                logPage("The Battle.net code page is still shown after submitting the code (" + filled + "/"
                        + slotCount + " slots filled)");
                logExchanges(responses);
            }
        }

        /** Logs the requests seen while submitting the code and the page's visible text (e-mails masked). */
        private void logExchanges(List<Response> responses) {
            try {
                List<String> exchanges = new ArrayList<>();
                synchronized (responses) {
                    for (Response r : responses) {
                        String body = null;
                        if (r.status() >= 400) {
                            try {
                                body = r.text();
                            } catch (PlaywrightException e) {
                                body = "(body unavailable)";
                            }
                        }
                        exchanges.add(LoginPageInspection.describeExchange(r.request().method(), r.url(), r.status(),
                                body));
                    }
                }
                Object text = page.evaluate("() => document.body ? document.body.innerText : ''");
                log.warn("Battle.net code page after submitting: requests={} text={}", exchanges,
                        LoginPageInspection.describeText(text == null ? null : text.toString()));
            } catch (RuntimeException e) {
                log.warn("Battle.net code page diagnostic failed ({})", e.getClass().getSimpleName());
            }
        }

        /** True when the code input is shown empty, or with an alert (a rejected code). */
        private boolean awaitsCode() {
            Locator input = page.locator(CODE_INPUT);
            if (input.count() == 0) return false;
            if (page.locator(LOGIN_ERROR).count() > 0) return true;
            try {
                return input.first().inputValue(new Locator.InputValueOptions().setTimeout(QUICK_CLICK_TIMEOUT_MS))
                        .isBlank();
            } catch (PlaywrightException e) {
                // Detached by a navigation: the next poll looks at the new page.
                return false;
            }
        }

        /**
         * Clicks the first match if visible; a race with a navigation is not an error.
         *
         * @return whether the click was sent
         */
        private boolean clickIfVisible(String selector) {
            Locator target = page.locator(selector);
            if (target.count() == 0) return false;
            try {
                target.first().click(new Locator.ClickOptions().setTimeout(QUICK_CLICK_TIMEOUT_MS));
                return true;
            } catch (PlaywrightException e) {
                log.warn("Click on the Battle.net login control {} failed ({})", selector,
                        e.getClass().getSimpleName());
                return false;
            }
        }

        /** Logs what the page shows when nothing was recognised, so a new Blizzard page shows up in the logs. */
        private void logUnrecognisedPage() {
            logPage("Battle.net login page not recognised before the timeout");
        }

        /** Logs the page's URL path, title and visible controls (never a value, e-mails masked). */
        private void logPage(String what) {
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
                log.warn("{}: {}", what, LoginPageInspection.describe(page.url(), page.title(), elements));
            } catch (RuntimeException e) {
                log.warn("{} (diagnostic failed: {})", what, e.getClass().getSimpleName());
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
