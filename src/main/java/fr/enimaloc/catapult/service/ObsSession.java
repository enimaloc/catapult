package fr.enimaloc.catapult.service;

import java.util.Map;
import java.util.Set;

/**
 * Snapshot of one OBS-script report: the client OS, machine-level environment variables
 * (used to expand placeholders like %APPDATA% in predicate values), and the list of
 * currently running processes with their working directories.
 */
public record ObsSession(
        String os,
        Map<String, String> environment,
        Set<ProcessSnapshot> processes
) {
    public static final ObsSession EMPTY = new ObsSession(null, Map.of(), Set.of());

    public record ProcessSnapshot(String name, String workingDirectory, String cmdline) {}
}
