package fr.enimaloc.catapult.thymeleaf;

import org.thymeleaf.context.ITemplateContext;
import org.thymeleaf.engine.AttributeName;
import org.thymeleaf.model.IProcessableElementTag;
import org.thymeleaf.processor.element.AbstractAttributeTagProcessor;
import org.thymeleaf.processor.element.IElementTagStructureHandler;
import org.thymeleaf.templatemode.TemplateMode;

/**
 * Processes {@code spa:switch="name:EventName.field=value,name:EventName.field=a/b"}:
 * the switch/case counterpart of {@code spa:on} — same flag-merge-then-apply pipeline
 * (see visibility.js's {@code Visibility.dispatch}, which treats {@code data-switch}
 * identically to {@code data-on}), but a field may carry {@code =value} (or
 * {@code =value1/value2} for an OR of literals) to match a specific enum-style value
 * instead of a plain boolean. Named separately from {@code spa:on} so templates reading
 * like a switch/case (one flag per possible status) stay readable, even though both
 * ultimately feed the same {@code spa:if}. Emits {@code data-switch} verbatim after
 * validating each entry has a {@code name:} prefix.
 */
public class SwitchAttributeProcessor extends AbstractAttributeTagProcessor {

    private static final String ATTR_NAME = "switch";
    private static final int PRECEDENCE = 1000;

    public SwitchAttributeProcessor(String dialectPrefix) {
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
                        "Invalid spa:switch entry \"" + entry + "\": expected \"name:EventName.field=value\" (full attribute value: \"" + attributeValue + "\")");
            }
        }
        structureHandler.setAttribute("data-switch", attributeValue);
    }
}
