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
 * Processor for {@code ws:get|post|put|delete="<url-expression>"}.
 * Resolves the Thymeleaf expression on the right side and writes the value
 * into {@code data-ws-path} alongside a {@code data-ws-method} pinned to
 * the HTTP verb the attribute name implies.
 *
 * <p>The original {@code ws:*} attribute is removed from the output so the
 * browser does not see a non-standard attribute on the element.</p>
 */
class WsMethodAttributeProcessor extends AbstractAttributeTagProcessor {

    private static final int PRECEDENCE = 1100;

    private final String httpMethod;

    WsMethodAttributeProcessor(String dialectPrefix, String attributeName, String httpMethod) {
        super(
                TemplateMode.HTML,
                dialectPrefix,
                null,           // any element
                false,
                attributeName,  // ws:<attributeName>
                true,
                PRECEDENCE,
                true            // remove the source attribute after processing
        );
        this.httpMethod = httpMethod;
    }

    @Override
    protected void doProcess(ITemplateContext context,
                             IProcessableElementTag tag,
                             AttributeName attributeName,
                             String attributeValue,
                             IElementTagStructureHandler handler) {
        IStandardExpressionParser parser = StandardExpressions.getExpressionParser(context.getConfiguration());
        IStandardExpression expr = parser.parseExpression(context, attributeValue);
        Object resolved = expr.execute(context);
        String path = resolved == null ? "" : resolved.toString();
        handler.setAttribute("data-ws-method", httpMethod);
        handler.setAttribute("data-ws-path", path);
        // Prevent htmx boost from intercepting ws-driven elements — ws-actions.js
        // handles their submit/click via WebSocket, not via HTTP.
        handler.setAttribute("hx-boost", "false");
    }
}
