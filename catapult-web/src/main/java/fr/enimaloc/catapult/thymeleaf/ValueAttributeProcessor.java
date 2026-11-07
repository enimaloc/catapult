package fr.enimaloc.catapult.thymeleaf;

import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * Processes {@code spa:value="property:EventName.field,property2:EventName2.!field2"}:
 * declares a direct DOM property assignment (not a visibility toggle — see {@code spa:on}
 * for that) driven by an SSE event field, with no Thymeleaf expression involved. Emits
 * {@code data-value} verbatim after validating each entry has a {@code property:} prefix;
 * see visibility.js's {@code Visibility.dispatch} for how it is interpreted client-side.
 *
 * <p>Grammar per comma-separated {@code property:spec} entry — same {@code spec} grammar
 * as {@code spa:on}:
 * <ul>
 *   <li>{@code EventName.field} — {@code el[property] = event.field} verbatim</li>
 *   <li>{@code EventName.!field} — {@code el[property] = !truthy(event.field)}</li>
 *   <li>{@code EventName} / {@code !EventName} — {@code el[property]} set to whether the
 *       event fired in that (non-)negated form, ignoring any field</li>
 * </ul>
 */
public class ValueAttributeProcessor extends AbstractAttributeTagProcessor {

    private static final String ATTR_NAME = "value";
    private static final int PRECEDENCE = 1000;

    public ValueAttributeProcessor(String dialectPrefix) {
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
                        "Invalid spa:value entry \"" + entry + "\": expected \"property:EventName[.field]\" (full attribute value: \"" + attributeValue + "\")");
            }
        }
        structureHandler.setAttribute("data-value", attributeValue);
    }
}
