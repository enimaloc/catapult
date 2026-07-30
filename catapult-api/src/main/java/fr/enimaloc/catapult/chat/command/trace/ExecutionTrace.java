package fr.enimaloc.catapult.chat.command.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ExecutionTrace {

    private final List<TraceEntry> entries = new ArrayList<>();
    private String finalOutput = "";

    public void record(TraceEntry entry) {
        entries.add(entry);
    }

    public void finish(String output) {
        this.finalOutput = output;
    }

    public List<TraceEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public String finalOutput() {
        return finalOutput;
    }
}
