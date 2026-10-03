package fr.enimaloc.catapult.thymeleaf;

import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * Processes {@code spa:in="property:EventName.arrayField"}: declares a DOM property
 * (typically {@code checked}) set to whether this element's own {@code value} is a
 * member of the named array field on an SSE event's payload — the generic form of the
 * "checkbox group membership" pattern every checkbox-group setter hand-rolled as
 * {@code cb.checked = someArray.includes(cb.value)}. Emits {@code data-in} verbatim
 * after validating each entry has a {@code property:} prefix; see visibility.js's
 * {@code Visibility.dispatch} for how it is interpreted client-side.
 */
public class InAttributeProcessor extends AbstractAttributeTagProcessor {

    private static final String ATTR_NAME = "in";
    private static final int PRECEDENCE = 1000;

    public InAttributeProcessor(String dialectPrefix) {
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
                        "Invalid spa:in entry \"" + entry + "\": expected \"property:EventName.arrayField\" (full attribute value: \"" + attributeValue + "\")");
            }
        }
        structureHandler.setAttribute("data-in", attributeValue);
    }
}
