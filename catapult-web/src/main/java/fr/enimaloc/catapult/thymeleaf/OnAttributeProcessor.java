package fr.enimaloc.catapult.thymeleaf;

import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * Processes {@code spa:on="name:EventName.field,name2:EventName2.!field2,name3:EventName3"}:
 * a purely declarative mapping from SSE event fields to the flag names {@code spa:if}
 * already renders as {@code data-if}. Unlike {@code spa:if}, no Thymeleaf expression is
 * involved here — the value is opaque to the server and only interpreted client-side at
 * event time (see visibility.js's {@code Visibility.dispatch}), so new SSE-driven
 * visibility rules never require a JS change: declare the mapping in the template and
 * the existing generic dispatcher picks it up.
 *
 * <p>Grammar per comma-separated {@code name:spec} entry:
 * <ul>
 *   <li>{@code EventName.field} — flag := truthy(event.field) (an array field is
 *       truthy when non-empty, not merely present)</li>
 *   <li>{@code EventName.!field} — flag := !truthy(event.field)</li>
 *   <li>{@code EventName} (no field) — flag := true whenever this event fires; use this
 *       when the event's mere occurrence is the signal, not one of its fields</li>
 *   <li>{@code !EventName} — flag := false whenever this event fires</li>
 * </ul>
 *
 * <p>Only the presence of a {@code name:} prefix is validated server-side; the {@code spec}
 * itself is forwarded verbatim; see visibility.js for how it is parsed and evaluated.
 */
public class OnAttributeProcessor extends AbstractAttributeTagProcessor {

    private static final String ATTR_NAME = "on";
    private static final int PRECEDENCE = 1000;

    public OnAttributeProcessor(String dialectPrefix) {
        super(TemplateMode.HTML, dialectPrefix, null, false, ATTR_NAME, true, PRECEDENCE, true);
    }

    @Override
    protected void doProcess(ITemplateContext context, IProcessableElementTag tag,
                              AttributeName attributeName, String attributeValue,
                              IElementTagStructureHandler structureHandler) {
        for (String entry : attributeValue.split(",")) {
            int sep = entry.indexOf(':');
            if (sep <= 0) {
                throw new IllegalArgumentException(
                        "Invalid spa:on entry \"" + entry + "\": expected \"name:EventName[.field]\" (full attribute value: \"" + attributeValue + "\")");
            }
        }
        structureHandler.setAttribute("data-on", attributeValue);
    }
}
