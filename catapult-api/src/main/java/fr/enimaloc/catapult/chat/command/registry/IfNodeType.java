package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.ast.CommandNode;
import fr.enimaloc.catapult.chat.command.ast.IfNode;
import org.springframework.stereotype.Component;

@Component
public class IfNodeType implements StructuralNodeType {
    @Override
    public String keyword() {
        return "if";
    }

    @Override
    public boolean handles(CommandNode node) {
        return node instanceof IfNode;
    }
}
