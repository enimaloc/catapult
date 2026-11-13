package fr.enimaloc.catapult.service.battlenet.login;

import org.jspecify.annotations.Nullable;

import java.time.Duration;

/**
 * Headless browser driving the Battle.net login page (spec §9). The page computes the SRP proof itself; the
 * login ends on a redirect to {@code http://localhost:0/?ST=EU-...} whose {@code ST} value is the ticket.
 */
public interface BattleNetLoginBrowser {

    /** Opens the login page in a fresh context, loaded with the account's trusted storageState when known. */
    LoginPage open(@Nullable String storageState);

    interface LoginPage extends AutoCloseable {

        void submitCredentials(String email, String password);

        /**
         * Blocks until the ticket redirect, the code page or a rejection, at most {@code timeout}
         * ({@link Outcome.TimedOut} then). Technical failures (crash, navigation error) are thrown.
         */
        Outcome awaitOutcome(Duration timeout);

        /** Fills the 2FA code page and submits it; follow with {@link #awaitOutcome}. */
        void submitCode(String code);

        /** The context's storageState (cookies and local storage) to reload on the next login. */
        String storageState();

        @Override
        void close();
    }

    sealed interface Outcome {

        record Ticket(String value) implements Outcome {
            @Override
            public String toString() {
                return "Ticket[value=***]";
            }
        }

        record CodeRequired() implements Outcome {
        }

        /**
         * The page answered with something that is neither a ticket nor a code request (wrong password, locked
         * account...): retrying the same credentials is pointless. {@code reason} is shown to the admin and
         * logged: it never carries a credential or a ticket.
         */
        record Failed(String reason) implements Outcome {
        }

        /** No answer within the timeout: worth retrying later. */
        record TimedOut() implements Outcome {
        }
    }
}
