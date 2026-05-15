package fr.enimaloc.catapult.service;

import fr.enimaloc.catapult.domain.ProcessBinding;
import fr.enimaloc.catapult.domain.ProcessPredicate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ProcessPredicateEvaluator {

    private static final Pattern WINDOWS_VAR = Pattern.compile("%([^%]+)%");
    private static final Pattern UNIX_VAR    = Pattern.compile("\\$(?:\\{([A-Za-z_][A-Za-z0-9_]*)\\}|([A-Za-z_][A-Za-z0-9_]*))");

    /**
     * Returns true if all applicable predicates of the binding match the given process snapshot.
     * Predicates targeting a different OS are skipped entirely (not counted toward AND/OR).
     * An empty applicable predicate list always matches.
     */
    public boolean evaluate(ProcessBinding binding, ObsSession.ProcessSnapshot proc, ObsSession session) {
        List<ProcessPredicate> applicable = binding.getPredicates().stream()
                .filter(p -> p.getOsTarget().appliesTo(session.os()))
                .toList();

        if (applicable.isEmpty()) return true;

        boolean result = evaluateSingle(applicable.get(0), proc, session);
        for (int i = 1; i < applicable.size(); i++) {
            ProcessPredicate pred = applicable.get(i);
            boolean matches = evaluateSingle(pred, proc, session);
            result = pred.getConnector() == ProcessPredicate.Connector.AND
                    ? result && matches
                    : result || matches;
        }
        return result;
    }

    private boolean evaluateSingle(ProcessPredicate pred, ObsSession.ProcessSnapshot proc, ObsSession session) {
        String expanded = expandVars(pred.getValue(), session.environment(), session.os());
        return switch (pred.getType()) {
            case WORKING_DIRECTORY -> matchesWorkingDirectory(proc.workingDirectory(), expanded, session.os());
            case ENV_VAR           -> matchesEnvVar(session.environment(), pred.getKey(), expanded, session.os());
            case CMDLINE_CONTAINS  -> matchesCmdline(proc.cmdline(), expanded, session.os());
        };
    }

    private boolean matchesWorkingDirectory(String actual, String pattern, String os) {
        if (actual == null || pattern == null) return false;
        boolean isWindows = "WINDOWS".equalsIgnoreCase(os);
        // Prefix mode when pattern ends with a path separator
        boolean prefix = pattern.endsWith("/") || pattern.endsWith("\\");
        if (isWindows) {
            String a = actual.replace('/', '\\');
            String p = pattern.replace('/', '\\');
            return prefix ? a.toLowerCase().startsWith(p.toLowerCase()) : a.equalsIgnoreCase(p);
        }
        return prefix ? actual.startsWith(pattern) : actual.equals(pattern);
    }

    private boolean matchesCmdline(String cmdline, String substring, String os) {
        if (cmdline == null || substring == null) return false;
        return "WINDOWS".equalsIgnoreCase(os)
                ? cmdline.toLowerCase().contains(substring.toLowerCase())
                : cmdline.contains(substring);
    }

    private boolean matchesEnvVar(Map<String, String> env, String key, String expected, String os) {
        if (env == null || key == null || key.isBlank()) return false;
        String actual = env.get(key);
        if (actual == null) return false;
        return "WINDOWS".equalsIgnoreCase(os) ? actual.equalsIgnoreCase(expected) : actual.equals(expected);
    }

    String expandVars(String value, Map<String, String> env, String os) {
        if (value == null || env == null || env.isEmpty()) return value;
        Pattern pattern = "WINDOWS".equalsIgnoreCase(os) ? WINDOWS_VAR : UNIX_VAR;
        Matcher m = pattern.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            // WINDOWS_VAR has one group; UNIX_VAR has two (${VAR} or $VAR) — first non-null wins
            String varName = m.group(1) != null ? m.group(1) : m.groupCount() > 1 ? m.group(2) : null;
            String replacement = varName != null ? env.getOrDefault(varName, m.group(0)) : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
