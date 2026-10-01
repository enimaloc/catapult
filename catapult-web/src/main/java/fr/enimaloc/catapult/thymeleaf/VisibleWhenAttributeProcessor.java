package fr.enimaloc.catapult.thymeleaf;

import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.standard.expression.IStandardExpression;
import org.thymeleaf.standard.expression.IStandardExpressionParser;
import org.thymeleaf.standard.expression.StandardExpressions;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.util.EvaluationUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Processes {@code sp:visible-when="name:${expr},name2:${expr2}"}: evaluates each
 * expression against the current template context (same engine {@code th:classappend}
 * uses), appends {@code hidden} to {@code class} unless every expression is true, and
 * always emits {@code data-visible-when="name,name2"} so visibility.js can re-evaluate
 * the same named flags client-side from SSE data without re-deriving the condition.
 *
 * <p>The {@code name:expr} pairs are split on a bare {@code ,}, so an expression that
 * itself contains a comma (e.g. {@code #{msg(a,b)}} or {@code #lists.contains(a, b)})
 * is not supported — keep such logic in a method on the model object instead and
 * reference it as a single no-comma expression.
 *
 * <p>Runs at the same precedence as {@code th:class} ({@value #PRECEDENCE}); combining
 * {@code sp:visible-when} with {@code th:class} on the same element has unspecified
 * write-order and can silently drop {@code hidden}. No template does this today — use
 * {@code th:classappend} (precedence 1100) instead if that combination is ever needed.
 */
public class VisibleWhenAttributeProcessor extends AbstractAttributeTagProcessor {

    private static final String ATTR_NAME = "visible-when";
    private static final int PRECEDENCE = 1000;

    public VisibleWhenAttributeProcessor(String dialectPrefix) {
        super(TemplateMode.HTML, dialectPrefix, null, false, ATTR_NAME, true, PRECEDENCE, true);
    }

    @Override
    protected void doProcess(ITemplateContext context, IProcessableElementTag tag,
                              AttributeName attributeName, String attributeValue,
                              IElementTagStructureHandler structureHandler) {
        IStandardExpressionParser parser = StandardExpressions.getExpressionParser(context.getConfiguration());
        List<String> names = new ArrayList<>();
        boolean visible = true;

        for (String pair : attributeValue.split(",")) {
            int sep = pair.indexOf(':');
            if (sep <= 0) {
                throw new IllegalArgumentException(
                        "Invalid sp:visible-when entry \"" + pair + "\": expected \"name:${expression}\" (full attribute value: \"" + attributeValue + "\")");
            }
            String name = pair.substring(0, sep).trim();
            String expr = pair.substring(sep + 1).trim();
            names.add(name);

            IStandardExpression expression = parser.parseExpression(context, expr);
            Object result = expression.execute(context);
            // Same truthiness Thymeleaf itself uses for th:if/th:classappend (a non-empty
            // String, a non-null object, etc. all count as true) — not a literal
            // Boolean.TRUE check, which would silently hide anything not already boolean.
            if (!EvaluationUtils.evaluateAsBoolean(result)) {
                visible = false;
            }
        }

        if (!visible) {
            String existingClass = tag.getAttributeValue("class");
            String newClass = (existingClass == null || existingClass.isBlank())
                    ? "hidden"
                    : existingClass + " hidden";
            structureHandler.setAttribute("class", newClass);
        }

        structureHandler.setAttribute("data-visible-when", String.join(",", names));
    }
}
