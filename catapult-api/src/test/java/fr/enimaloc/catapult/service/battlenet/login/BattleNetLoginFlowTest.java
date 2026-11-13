package fr.enimaloc.catapult.service.battlenet.login;

import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginBrowser.Outcome;
import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginFlow.Result;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BattleNetLoginFlowTest {

    private static final Duration TICKET_TIMEOUT = Duration.ofSeconds(60);

    private final FakeLoginBrowser browser = new FakeLoginBrowser();
    private final AtomicInteger codeRequests = new AtomicInteger();

    private BattleNetLoginFlow flow(boolean interactive, Duration codeTimeout) {
        return new BattleNetLoginFlow(interactive, TICKET_TIMEOUT, codeTimeout, codeRequests::incrementAndGet);
    }

    @Test
    void ticketOnFirstTryReturnsTheTicketAndTheNewStorageState() {
        browser.script(new Outcome.Ticket("EU-ticket"));
        var page = browser.open(null);

        Result result = flow(true, Duration.ofMinutes(10)).run(page, "bot@example.com", "secret");

        assertThat(result).isEqualTo(new Result.Success("EU-ticket", "{\"cookies\":[\"fresh\"]}"));
        assertThat(browser.lastPage().credentials).containsExactly("bot@example.com", "secret");
        assertThat(codeRequests).hasValue(0);
    }

    @Test
    void interactiveFlowWaitsForTheCodeThenResumes() throws Exception {
        browser.script(new Outcome.CodeRequired(), new Outcome.Ticket("EU-ticket"));
        var page = browser.open(null);
        BattleNetLoginFlow flow = flow(true, Duration.ofMinutes(10));

        CompletableFuture<Result> running = CompletableFuture.supplyAsync(() -> flow.run(page, "e", "p"));
        awaitWaiting(flow);
        flow.submitCode("123456");

        assertThat(running.get(5, TimeUnit.SECONDS)).isInstanceOf(Result.Success.class);
        assertThat(browser.lastPage().codes).containsExactly("123456");
        assertThat(codeRequests).hasValue(1);
        assertThat(flow.waitingForCode()).isFalse();
    }

    @Test
    void interactiveFlowFailsWhenTheCodeNeverComes() {
        browser.script(new Outcome.CodeRequired());
        var page = browser.open(null);

        Result result = flow(true, Duration.ofMillis(50)).run(page, "e", "p");

        assertThat(result).isInstanceOf(Result.Failed.class);
        assertThat(browser.lastPage().codes).isEmpty();
    }

    @Test
    void automaticFlowStopsAtTheCodePageWithoutWaiting() {
        browser.script(new Outcome.CodeRequired());
        var page = browser.open(null);

        Result result = flow(false, Duration.ofMinutes(10)).run(page, "e", "p");

        assertThat(result).isEqualTo(new Result.CodeRequired());
        assertThat(codeRequests).hasValue(0);
    }

    @Test
    void submitCodeOutsideTheCodeWaitIsRejected() {
        BattleNetLoginFlow flow = flow(true, Duration.ofMinutes(10));

        assertThatThrownBy(() -> flow.submitCode("123456")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failedOutcomeIsARejection() {
        browser.script(new Outcome.Failed("wrong password"));

        Result result = flow(false, Duration.ofMinutes(10)).run(browser.open(null), "e", "p");

        assertThat(result).isEqualTo(new Result.Rejected("wrong password"));
    }

    @Test
    void timeoutIsARetryableFailure() {
        browser.script(new Outcome.TimedOut());

        Result result = flow(false, Duration.ofMinutes(10)).run(browser.open(null), "e", "p");

        assertThat(result).isInstanceOf(Result.Failed.class);
    }

    @Test
    void ticketValuesAreMaskedInToString() {
        assertThat(new Outcome.Ticket("EU-secret-ticket").toString()).doesNotContain("EU-secret-ticket");
        assertThat(new Result.Success("EU-secret-ticket", "{\"state\"}").toString())
                .doesNotContain("EU-secret-ticket").doesNotContain("state");
    }

    static void awaitWaiting(BattleNetLoginFlow flow) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!flow.waitingForCode()) {
            if (System.nanoTime() > deadline) throw new AssertionError("flow never waited for a code");
            Thread.sleep(5);
        }
    }
}
