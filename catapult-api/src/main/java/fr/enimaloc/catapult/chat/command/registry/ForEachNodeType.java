package fr.enimaloc.catapult.chat.command.registry;

import fr.enimaloc.catapult.chat.command.ast.ForEachStatement;
import fr.enimaloc.catapult.chat.command.ast.Statement;
import org.springframework.stereotype.Component;

@Component
public class ForEachNodeType implements StructuralNodeType {
    @Override
    public String keyword() {
        return "for";
    }

    @Override
    public boolean handles(Statement statement) {
        return statement instanceof ForEachStatement;
    }
}
