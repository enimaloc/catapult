package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.ProcessPredicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProcessPredicateEvaluatorTest {

    private ProcessPredicateEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new ProcessPredicateEvaluator();
    }

    // ── expandVars ────────────────────────────────────────────────────────────

    @Test
    void expandVars_windows_replacesPercent() {
        String result = evaluator.expandVars("%APPDATA%\\MyGame", Map.of("APPDATA", "C:\\Users\\Alice\\AppData\\Roaming"), "WINDOWS");
        assertThat(result).isEqualTo("C:\\Users\\Alice\\AppData\\Roaming\\MyGame");
    }

    @Test
    void expandVars_windows_unknownVar_keepsPlaceholder() {
        String result = evaluator.expandVars("%UNKNOWN%\\Game", Map.of(), "WINDOWS");
        assertThat(result).isEqualTo("%UNKNOWN%\\Game");
    }

    @Test
    void expandVars_unix_braceForm() {
        String result = evaluator.expandVars("${HOME}/games", Map.of("HOME", "/home/alice"), "LINUX");
        assertThat(result).isEqualTo("/home/alice/games");
    }

    @Test
    void expandVars_unix_bareForm() {
        String result = evaluator.expandVars("$HOME/games", Map.of("HOME", "/home/alice"), "LINUX");
        assertThat(result).isEqualTo("/home/alice/games");
    }

    @Test
    void expandVars_unix_malformedBrace_notMatched() {
        // "${VAR without closing brace should NOT be matched/corrupted
        String result = evaluator.expandVars("${HOME/games", Map.of("HOME", "/home/alice"), "LINUX");
        assertThat(result).isEqualTo("${HOME/games");
    }

    @Test
    void expandVars_nullValue_returnsNull() {
        assertThat(evaluator.expandVars(null, Map.of("X", "y"), "LINUX")).isNull();
    }

    @Test
    void expandVars_emptyEnv_returnsOriginal() {
        assertThat(evaluator.expandVars("%APPDATA%\\foo", Map.of(), "WINDOWS")).isEqualTo("%APPDATA%\\foo");
    }

    // ── Working directory ─────────────────────────────────────────────────────

    @Test
    void evaluate_workingDir_exactMatch_windows() {
        ProcessBinding binding = bindingWith(pred(ProcessPredicate.PredicateType.WORKING_DIRECTORY, null, "C:\\Games\\HL2", ProcessPredicate.OsTarget.WINDOWS));
        ObsSession session = new ObsSession("WINDOWS", Map.of(), java.util.Set.of());
        ObsSession.ProcessSnapshot proc = new ObsSession.ProcessSnapshot("hl2", "C:\\Games\\HL2", null);
        assertThat(evaluator.evaluate(binding, proc, session)).isTrue();
    }

    @Test
    void evaluate_workingDir_exactMatch_caseInsensitive_windows() {
        ProcessBinding binding = bindingWith(pred(ProcessPredicate.PredicateType.WORKING_DIRECTORY, null, "c:\\games\\hl2", ProcessPredicate.OsTarget.WINDOWS));
        ObsSession session = new ObsSession("WINDOWS", Map.of(), java.util.Set.of());
        ObsSession.ProcessSnapshot proc = new ObsSession.ProcessSnapshot("hl2", "C:\\Games\\HL2", null);
        assertThat(evaluator.evaluate(binding, proc, session)).isTrue();
    }

    @Test
    void evaluate_workingDir_prefixMode_trailingSlash() {
        ProcessBinding binding = bindingWith(pred(ProcessPredicate.PredicateType.WORKING_DIRECTORY, null, "C:\\Games\\", ProcessPredicate.OsTarget.WINDOWS));
        ObsSession session = new ObsSession("WINDOWS", Map.of(), java.util.Set.of());
        ObsSession.ProcessSnapshot proc = new ObsSession.ProcessSnapshot("hl2", "C:\\Games\\HL2", null);
        assertThat(evaluator.evaluate(binding, proc, session)).isTrue();
    }

    @Test
    void evaluate_workingDir_nullActual_fails() {
        ProcessBinding binding = bindingWith(pred(ProcessPredicate.PredicateType.WORKING_DIRECTORY, null, "C:\\Games", ProcessPredicate.OsTarget.ALL));
        ObsSession session = new ObsSession("WINDOWS", Map.of(), java.util.Set.of());
        ObsSession.ProcessSnapshot proc = new ObsSession.ProcessSnapshot("hl2", null, null);
        assertThat(evaluator.evaluate(binding, proc, session)).isFalse();
    }

    @Test
    void evaluate_workingDir_withEnvExpansion() {
        ProcessBinding binding = bindingWith(pred(ProcessPredicate.PredicateType.WORKING_DIRECTORY, null, "%APPDATA%\\MyGame", ProcessPredicate.OsTarget.WINDOWS));
        ObsSession session = new ObsSession("WINDOWS", Map.of("APPDATA", "C:\\Users\\Alice\\AppData\\Roaming"), java.util.Set.of());
        ObsSession.ProcessSnapshot proc = new ObsSession.ProcessSnapshot("game", "C:\\Users\\Alice\\AppData\\Roaming\\MyGame", null);
        assertThat(evaluator.evaluate(binding, proc, session)).isTrue();
    }

    // ── OS targeting ──────────────────────────────────────────────────────────

    @Test
    void evaluate_osTargetWindowsOnly_skippedOnLinux() {
        ProcessBinding binding = bindingWith(pred(ProcessPredicate.PredicateType.WORKING_DIRECTORY, null, "C:\\Games", ProcessPredicate.OsTarget.WINDOWS));
        ObsSession session = new ObsSession("LINUX", Map.of(), java.util.Set.of());
        // Predicate is Windows-only, skip → binding matches (no applicable predicates)
        assertThat(evaluator.evaluate(binding, new ObsSession.ProcessSnapshot("game", "/home/other", null), session)).isTrue();
    }

    // ── AND / OR ──────────────────────────────────────────────────────────────

    @Test
    void evaluate_andChain_allPass() {
        ProcessPredicate p1 = pred(ProcessPredicate.PredicateType.WORKING_DIRECTORY, null, "C:\\Games\\", ProcessPredicate.OsTarget.ALL);
        ProcessPredicate p2 = pred(ProcessPredicate.PredicateType.ENV_VAR, "MY_VAR", "expected", ProcessPredicate.OsTarget.ALL);
        p2.setConnector(ProcessPredicate.Connector.AND);
        ProcessBinding binding = bindingWith(p1, p2);
        ObsSession session = new ObsSession("WINDOWS", Map.of("MY_VAR", "expected"), java.util.Set.of());
        assertThat(evaluator.evaluate(binding, new ObsSession.ProcessSnapshot("game", "C:\\Games\\Sub", null), session)).isTrue();
    }

    @Test
    void evaluate_andChain_oneFails() {
        ProcessPredicate p1 = pred(ProcessPredicate.PredicateType.WORKING_DIRECTORY, null, "C:\\Games\\", ProcessPredicate.OsTarget.ALL);
        ProcessPredicate p2 = pred(ProcessPredicate.PredicateType.ENV_VAR, "MY_VAR", "expected", ProcessPredicate.OsTarget.ALL);
        p2.setConnector(ProcessPredicate.Connector.AND);
        ProcessBinding binding = bindingWith(p1, p2);
        ObsSession session = new ObsSession("WINDOWS", Map.of("MY_VAR", "wrong"), java.util.Set.of());
        assertThat(evaluator.evaluate(binding, new ObsSession.ProcessSnapshot("game", "C:\\Games\\Sub", null), session)).isFalse();
    }

    @Test
    void evaluate_orChain_firstFails_secondPasses() {
        ProcessPredicate p1 = pred(ProcessPredicate.PredicateType.WORKING_DIRECTORY, null, "C:\\Games\\A\\", ProcessPredicate.OsTarget.ALL);
        ProcessPredicate p2 = pred(ProcessPredicate.PredicateType.WORKING_DIRECTORY, null, "C:\\Games\\B\\", ProcessPredicate.OsTarget.ALL);
        p2.setConnector(ProcessPredicate.Connector.OR);
        ProcessBinding binding = bindingWith(p1, p2);
        ObsSession session = new ObsSession("WINDOWS", Map.of(), java.util.Set.of());
        assertThat(evaluator.evaluate(binding, new ObsSession.ProcessSnapshot("game", "C:\\Games\\B\\Sub", null), session)).isTrue();
    }

    @Test
    void evaluate_noPredicates_alwaysMatches() {
        ProcessBinding binding = new ProcessBinding();
        ObsSession session = new ObsSession("WINDOWS", Map.of(), java.util.Set.of());
        assertThat(evaluator.evaluate(binding, new ObsSession.ProcessSnapshot("game", null, null), session)).isTrue();
    }

    // ── CMDLINE_CONTAINS ──────────────────────────────────────────────────────

    @Test
    void evaluate_cmdlineContains_matches() {
        ProcessBinding binding = bindingWith(pred(ProcessPredicate.PredicateType.CMDLINE_CONTAINS, null, "-game hl2", ProcessPredicate.OsTarget.ALL));
        ObsSession session = new ObsSession("WINDOWS", Map.of(), java.util.Set.of());
        ObsSession.ProcessSnapshot proc = new ObsSession.ProcessSnapshot("hl2", null, "hl2.exe -game hl2 -steam");
        assertThat(evaluator.evaluate(binding, proc, session)).isTrue();
    }

    @Test
    void evaluate_cmdlineContains_noMatch() {
        ProcessBinding binding = bindingWith(pred(ProcessPredicate.PredicateType.CMDLINE_CONTAINS, null, "-game tf2", ProcessPredicate.OsTarget.ALL));
        ObsSession session = new ObsSession("WINDOWS", Map.of(), java.util.Set.of());
        ObsSession.ProcessSnapshot proc = new ObsSession.ProcessSnapshot("hl2", null, "hl2.exe -game hl2 -steam");
        assertThat(evaluator.evaluate(binding, proc, session)).isFalse();
    }

    @Test
    void evaluate_cmdlineContains_caseInsensitive_windows() {
        ProcessBinding binding = bindingWith(pred(ProcessPredicate.PredicateType.CMDLINE_CONTAINS, null, "-GAME HL2", ProcessPredicate.OsTarget.WINDOWS));
        ObsSession session = new ObsSession("WINDOWS", Map.of(), java.util.Set.of());
        ObsSession.ProcessSnapshot proc = new ObsSession.ProcessSnapshot("hl2", null, "hl2.exe -game hl2");
        assertThat(evaluator.evaluate(binding, proc, session)).isTrue();
    }

    @Test
    void evaluate_cmdlineContains_caseSensitive_linux() {
        ProcessBinding binding = bindingWith(pred(ProcessPredicate.PredicateType.CMDLINE_CONTAINS, null, "-GAME hl2", ProcessPredicate.OsTarget.LINUX));
        ObsSession session = new ObsSession("LINUX", Map.of(), java.util.Set.of());
        ObsSession.ProcessSnapshot proc = new ObsSession.ProcessSnapshot("hl2", null, "hl2 -game hl2");
        assertThat(evaluator.evaluate(binding, proc, session)).isFalse();
    }

    @Test
    void evaluate_cmdlineContains_nullCmdline_fails() {
        ProcessBinding binding = bindingWith(pred(ProcessPredicate.PredicateType.CMDLINE_CONTAINS, null, "-game hl2", ProcessPredicate.OsTarget.ALL));
        ObsSession session = new ObsSession("WINDOWS", Map.of(), java.util.Set.of());
        ObsSession.ProcessSnapshot proc = new ObsSession.ProcessSnapshot("hl2", null, null);
        assertThat(evaluator.evaluate(binding, proc, session)).isFalse();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static ProcessPredicate pred(ProcessPredicate.PredicateType type, String key, String value, ProcessPredicate.OsTarget osTarget) {
        ProcessPredicate p = new ProcessPredicate();
        p.setType(type);
        p.setKey(key);
        p.setValue(value);
        p.setOsTarget(osTarget);
        p.setConnector(ProcessPredicate.Connector.AND);
        return p;
    }

    private static ProcessBinding bindingWith(ProcessPredicate... predicates) {
        ProcessBinding binding = new ProcessBinding();
        for (int i = 0; i < predicates.length; i++) {
            predicates[i].setPosition(i);
            predicates[i].setBinding(binding);
            binding.getPredicates().add(predicates[i]);
        }
        return binding;
    }
}
