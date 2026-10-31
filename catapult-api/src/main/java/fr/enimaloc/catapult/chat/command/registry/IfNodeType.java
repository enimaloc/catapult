package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.ast.IfStatement;
import fr.enimaloc.catapult.chat.command.ast.Statement;
import org.springframework.stereotype.Component;

@Component
public class IfNodeType implements StructuralNodeType {
    @Override
    public String keyword() {
        return "if";
    }

    @Override
    public boolean handles(Statement statement) {
        return statement instanceof IfStatement;
    }
}
