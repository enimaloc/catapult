package fr.enimaloc.catapult.controller.mock;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Tells index.html it runs mocked ({@code mock}), so it adds the "Mock" button and mock-popout.js. */
@ControllerAdvice
@Profile("mock")
public class MockModelAdvice {

    @ModelAttribute("mock")
    public boolean mock() {
        return true;
    }
}
