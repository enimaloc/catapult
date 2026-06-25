package fr.enimaloc.catapult.web.thymeleaf;

import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.standard.expression.IStandardExpression;
import org.thymeleaf.standard.expression.IStandardExpressionParser;
import org.thymeleaf.standard.expression.StandardExpressions;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * Generic pass-through processor for {@code ws:target}/{@code ws:swap}/
 * {@code ws:trigger}. Writes the value into {@code data-ws-<dataSuffix>}.
 *
 * <p>Values that begin with a Thymeleaf expression marker
 * ({@code ${}, *{}, @{}, ~{}, #{}}) are resolved through the expression
 * parser. Everything else is treated as a literal string — letting authors
 * write the natural {@code ws:swap="outerHTML"} instead of the awkward
 * {@code ws:swap="'outerHTML'"}.</p>
 */
class WsValueAttributeProcessor extends AbstractAttributeTagProcessor {

    private static final int PRECEDENCE = 1100;

    private final String dataSuffix;

    WsValueAttributeProcessor(String dialectPrefix, String attributeName, String dataSuffix) {
        super(TemplateMode.HTML, dialectPrefix, null, false, attributeName, true, PRECEDENCE, true);
        this.dataSuffix = dataSuffix;
    }

    @Override
    protected void doProcess(ITemplateContext context,
                             IProcessableElementTag tag,
                             AttributeName attributeName,
                             String attributeValue,
                             IElementTagStructureHandler handler) {
        String out;
        if (looksLikeExpression(attributeValue)) {
            IStandardExpressionParser parser = StandardExpressions.getExpressionParser(context.getConfiguration());
            IStandardExpression expr = parser.parseExpression(context, attributeValue);
            Object resolved = expr.execute(context);
            out = resolved == null ? "" : resolved.toString();
        } else {
            out = attributeValue;
        }
        handler.setAttribute("data-ws-" + dataSuffix, out);
    }

    private static boolean looksLikeExpression(String v) {
        if (v == null || v.isEmpty()) return false;
        char c = v.charAt(0);
        return (c == '$' || c == '*' || c == '@' || c == '~' || c == '#')
                && v.length() > 1 && v.charAt(1) == '{';
    }
}
