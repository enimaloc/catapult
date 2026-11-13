package fr.enimaloc.catapult.service.battlenet.login;

import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginBrowser.LoginPage;
import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginBrowser.Outcome;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * One login attempt on an open page (spec §9.1). An interactive attempt (started by an admin) waits on the
 * code page for a code entered in Catapult, at most {@code codeTimeout} in total; an automatic one (re-mint
 * on reconnection) stops there, since nobody is around to read the code.
 */
@Slf4j
public final class BattleNetLoginFlow {

    public sealed interface Result {

        record Success(String ticket, @Nullable String storageState) implements Result {
            @Override
            public String toString() {
                return "Success[ticket=***, storageState=" + (storageState == null ? "null" : "***") + "]";
            }
        }

        record CodeRequired() implements Result {
        }

        /** Battle.net turned the login down: an admin must fix the account before any retry. */
        record Rejected(String reason) implements Result {
        }

        /** Timeout or technical failure: a later retry may succeed. */
        record Failed(String reason) implements Result {
        }
    }

    /** Reason of the {@link Result.Failed} returned when the login page never answered. */
    static final String TIMEOUT_REASON = "No answer from the login page in time";

    private final boolean interactive;
    private final Duration ticketTimeout;
    private final Duration codeTimeout;
    private final Runnable onCodeRequired;
    private final BlockingQueue<String> codes = new LinkedBlockingQueue<>();
    private volatile boolean waitingForCode;

    /** {@code onCodeRequired} runs on the flow's thread each time an interactive attempt starts waiting. */
    public BattleNetLoginFlow(boolean interactive, Duration ticketTimeout, Duration codeTimeout,
                              Runnable onCodeRequired) {
        this.interactive = interactive;
        this.ticketTimeout = ticketTimeout;
        this.codeTimeout = codeTimeout;
        this.onCodeRequired = onCodeRequired;
    }

    public boolean interactive() {
        return interactive;
    }

    public boolean waitingForCode() {
        return waitingForCode;
    }

    /** Hands the code to the waiting attempt. */
    public void submitCode(String code) {
        if (!waitingForCode) throw new IllegalStateException("No login is waiting for a code");
        codes.offer(code);
    }

    /** Runs the attempt on {@code page}; the caller closes the page. */
    public Result run(LoginPage page, String email, String password) {
        try {
            page.submitCredentials(email, password);
            Outcome outcome = page.awaitOutcome(ticketTimeout);
            long codeDeadline = 0;
            while (true) {
                switch (outcome) {
                    case Outcome.Ticket ticket -> {
                        return new Result.Success(ticket.value(), storageStateOf(page));
                    }
                    case Outcome.Failed failed -> {
                        return new Result.Rejected(failed.reason());
                    }
                    case Outcome.TimedOut ignored -> {
                        return new Result.Failed(TIMEOUT_REASON);
                    }
                    case Outcome.CodeRequired ignored -> {
                        if (!interactive) return new Result.CodeRequired();
                        if (codeDeadline == 0) codeDeadline = System.nanoTime() + codeTimeout.toNanos();
                        String code = awaitCode(codeDeadline);
                        if (code == null) return new Result.Failed("No code entered in time");
                        page.submitCode(code);
                        outcome = page.awaitOutcome(ticketTimeout);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result.Failed("Login interrupted");
        } catch (RuntimeException e) {
            // The exception message may quote the page URL, which carries the ticket: keep the type only.
            return new Result.Failed("Login page error (" + e.getClass().getSimpleName() + ")");
        }
    }

    private @Nullable String awaitCode(long deadline) throws InterruptedException {
        codes.clear();
        onCodeRequired.run();
        waitingForCode = true;
        try {
            return codes.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        } finally {
            waitingForCode = false;
        }
    }

    private static @Nullable String storageStateOf(LoginPage page) {
        try {
            return page.storageState();
        } catch (RuntimeException e) {
            log.warn("Could not read the storageState after a Battle.net login ({}): keeping the previous one",
                    e.getClass().getSimpleName());
            return null;
        }
    }
}
