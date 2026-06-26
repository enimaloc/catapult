package fr.enimaloc.catapult.web.thymeleaf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.standard.expression.IStandardExpression;
import org.thymeleaf.standard.expression.IStandardExpressionParser;
import org.thymeleaf.standard.expression.StandardExpressions;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WsMethodAttributeProcessorTest {

    @Mock ITemplateContext context;
    @Mock IEngineConfiguration configuration;
    @Mock IProcessableElementTag tag;
    @Mock AttributeName attributeName;
    @Mock IElementTagStructureHandler handler;
    @Mock IStandardExpressionParser parser;
    @Mock IStandardExpression expression;

    @BeforeEach
    void setUp() {
        when(context.getConfiguration()).thenReturn(configuration);
    }

    private void stubPath(String path) {
        when(parser.parseExpression(context, path)).thenReturn(expression);
        when(expression.execute(context)).thenReturn(path);
    }

    @Test
    void setsDataWsMethodAndPathAttributes() {
        try (MockedStatic<StandardExpressions> mocked = mockStatic(StandardExpressions.class)) {
            mocked.when(() -> StandardExpressions.getExpressionParser(configuration)).thenReturn(parser);
            stubPath("/test-path");

            WsMethodAttributeProcessor processor = new WsMethodAttributeProcessor("ws", "post", "POST");
            processor.doProcess(context, tag, attributeName, "/test-path", handler);

            verify(handler).setAttribute("data-ws-method", "POST");
            verify(handler).setAttribute("data-ws-path", "/test-path");
        }
    }

    @Test
    void setsHxBoostFalse_toOptOutOfHtmxBoost() {
        try (MockedStatic<StandardExpressions> mocked = mockStatic(StandardExpressions.class)) {
            mocked.when(() -> StandardExpressions.getExpressionParser(configuration)).thenReturn(parser);
            stubPath("/test-path");

            WsMethodAttributeProcessor processor = new WsMethodAttributeProcessor("ws", "post", "POST");
            processor.doProcess(context, tag, attributeName, "/test-path", handler);

            verify(handler).setAttribute("hx-boost", "false");
        }
    }

    @ParameterizedTest
    @CsvSource({
        "get,GET",
        "post,POST",
        "put,PUT",
        "delete,DELETE"
    })
    void setsHxBoostFalse_forAllHttpMethods(String attrName, String httpMethod) {
        try (MockedStatic<StandardExpressions> mocked = mockStatic(StandardExpressions.class)) {
            mocked.when(() -> StandardExpressions.getExpressionParser(configuration)).thenReturn(parser);
            stubPath("/test-path");

            WsMethodAttributeProcessor processor = new WsMethodAttributeProcessor("ws", attrName, httpMethod);
            processor.doProcess(context, tag, attributeName, "/test-path", handler);

            verify(handler).setAttribute("data-ws-method", httpMethod);
            verify(handler).setAttribute("hx-boost", "false");
        }
    }

    @Test
    void usesEmptyPath_whenExpressionResolvesToNull() {
        try (MockedStatic<StandardExpressions> mocked = mockStatic(StandardExpressions.class)) {
            mocked.when(() -> StandardExpressions.getExpressionParser(configuration)).thenReturn(parser);
            when(parser.parseExpression(context, "null-expr")).thenReturn(expression);
            when(expression.execute(context)).thenReturn(null);

            WsMethodAttributeProcessor processor = new WsMethodAttributeProcessor("ws", "get", "GET");
            processor.doProcess(context, tag, attributeName, "null-expr", handler);

            verify(handler).setAttribute("data-ws-path", "");
            verify(handler).setAttribute("hx-boost", "false");
        }
    }
}
