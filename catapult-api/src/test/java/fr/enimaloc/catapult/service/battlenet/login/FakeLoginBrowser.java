package fr.enimaloc.catapult.service.battlenet.login;

import fr.enimaloc.catapult.service.battlenet.login.BattleNetLoginBrowser.Outcome;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Scripted browser: every page returns the queued outcomes in order, then fails. */
class FakeLoginBrowser implements BattleNetLoginBrowser {

    final Deque<Outcome> outcomes = new ArrayDeque<>();
    final List<FakePage> pages = new CopyOnWriteArrayList<>();
    String storageStateAfterLogin = "{\"cookies\":[\"fresh\"]}";

    FakeLoginBrowser script(Outcome... script) {
        outcomes.addAll(List.of(script));
        return this;
    }

    @Override
    public LoginPage open(String storageState) {
        FakePage page = new FakePage(storageState);
        pages.add(page);
        return page;
    }

    FakePage lastPage() {
        return pages.getLast();
    }

    class FakePage implements LoginPage {

        final String openedWith;
        final List<String> credentials = new ArrayList<>();
        final List<String> codes = new CopyOnWriteArrayList<>();
        volatile boolean closed;

        FakePage(String openedWith) {
            this.openedWith = openedWith;
        }

        @Override
        public void submitCredentials(String email, String password) {
            credentials.add(email);
            credentials.add(password);
        }

        @Override
        public synchronized Outcome awaitOutcome(Duration timeout) {
            Outcome next = outcomes.poll();
            return next != null ? next : new Outcome.Failed("script exhausted");
        }

        @Override
        public void submitCode(String code) {
            codes.add(code);
        }

        @Override
        public String storageState() {
            return storageStateAfterLogin;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
