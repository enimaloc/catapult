package fr.enimaloc.catapult.web.thymeleaf;

import org.springframework.stereotype.Component;
import org.thymeleaf.dialect.AbstractProcessorDialect;
import org.thymeleaf.processor.IProcessor;
import org.thymeleaf.standard.StandardDialect;

import java.util.HashSet;
import java.util.Set;

/**
 * Custom Thymeleaf dialect exposing a {@code ws:} prefix that emits
 * {@code data-ws-*} attributes consumed by {@code ws-actions.js}. Replaces
 * the htmx-over-WS attribute set ({@code hx-get}/{@code hx-post}/...) with
 * a project-owned vocabulary tied to the catapult WebSocket protocol.
 *
 * <p>Usage:</p>
 * <pre>
 *   &lt;form ws:post="@{/channels/{u}/settings/bot(u=${channelUsername})}"&gt;
 *       &lt;button&gt;Toggle&lt;/button&gt;
 *   &lt;/form&gt;
 * </pre>
 *
 * <p>Each attribute reads the Thymeleaf expression on the right-hand side
 * (so {@code @{...}} URL expressions and {@code ${...}} variables work
 * exactly like in standard attributes) and writes the resulting value into
 * a corresponding {@code data-ws-*} HTML attribute. The client JS picks
 * those up at load time and wires up the event handlers.</p>
 *
 * <p>Precedence is set just above the standard dialect so that
 * {@code ws:*} attributes see Thymeleaf expressions already resolved.</p>
 */
@Component
public class WsDialect extends AbstractProcessorDialect {

    public static final String PREFIX = "ws";

    public WsDialect() {
        // Higher precedence number = processed later than standard dialect's
        // expression resolution; we read the resolved value.
        super("Catapult WS", PREFIX, StandardDialect.PROCESSOR_PRECEDENCE + 100);
    }

    @Override
    public Set<IProcessor> getProcessors(String dialectPrefix) {
        Set<IProcessor> out = new HashSet<>();
        // Method+path attributes — each writes data-ws-method=<verb>
        // and data-ws-path=<value>.
        out.add(new WsMethodAttributeProcessor(dialectPrefix, "get",    "GET"));
        out.add(new WsMethodAttributeProcessor(dialectPrefix, "post",   "POST"));
        out.add(new WsMethodAttributeProcessor(dialectPrefix, "put",    "PUT"));
        out.add(new WsMethodAttributeProcessor(dialectPrefix, "delete", "DELETE"));
        // Pass-through value attributes — written verbatim into data-ws-*.
        out.add(new WsValueAttributeProcessor(dialectPrefix, "target",  "target"));
        out.add(new WsValueAttributeProcessor(dialectPrefix, "swap",    "swap"));
        out.add(new WsValueAttributeProcessor(dialectPrefix, "trigger", "trigger"));
        // ws:after — JS expression evaluated after a successful swap completes.
        // Used by help-panel buttons: ws:after="openHelpPanel()".
        out.add(new WsValueAttributeProcessor(dialectPrefix, "after",   "after"));
        return out;
    }
}
