package fr.enimaloc.catapult.thymeleaf;

import fr.enimaloc.catapult.dto.integration.SpaState;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.standard.expression.StandardExpressions;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * Processes {@code spa:state="${expr}"}, where {@code expr} evaluates to a {@link SpaState}: the
 * DTO-driven equivalent of {@code spa:if} + {@code spa:on} + {@code spa:value}, for templates
 * whose flags come from the model instead of being spelled out in the markup. Appends
 * {@code hidden} unless every flag starts true, and emits {@code data-if}, {@code data-on} and
 * {@code data-value} for visibility.js. A null state leaves the element untouched.
 *
 * <p>Runs after {@code th:class}/{@code th:classappend} ({@value #PRECEDENCE}), so the
 * {@code hidden} it appends is added to their result instead of racing them.
 */
public class StateAttributeProcessor extends AbstractAttributeTagProcessor {

    private static final String ATTR_NAME = "state";
    private static final int PRECEDENCE = 1200;

    public StateAttributeProcessor(String dialectPrefix) {
        super(TemplateMode.HTML, dialectPrefix, null, false, ATTR_NAME, true, PRECEDENCE, true);
    }

    @Override
    protected void doProcess(ITemplateContext context, IProcessableElementTag tag,
                              AttributeName attributeName, String attributeValue,
                              IElementTagStructureHandler structureHandler) {
        Object result = StandardExpressions.getExpressionParser(context.getConfiguration())
                .parseExpression(context, attributeValue)
                .execute(context);
        if (result == null) {
            return;
        }
        if (!(result instanceof SpaState state)) {
            throw new IllegalArgumentException(
                    "spa:state=\"" + attributeValue + "\" must evaluate to a SpaState, got " + result.getClass().getName());
        }

        if (!state.visible()) {
            String existingClass = tag.getAttributeValue("class");
            structureHandler.setAttribute("class",
                    existingClass == null || existingClass.isBlank() ? "hidden" : existingClass + " hidden");
        }
        setIfPresent(structureHandler, "data-if", state.dataIf());
        setIfPresent(structureHandler, "data-on", state.dataOn());
        setIfPresent(structureHandler, "data-value", state.dataValue());
    }

    private static void setIfPresent(IElementTagStructureHandler structureHandler, String name, String value) {
        if (value != null) {
            structureHandler.setAttribute(name, value);
        }
    }
}
