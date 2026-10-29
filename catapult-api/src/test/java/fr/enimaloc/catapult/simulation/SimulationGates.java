package fr.enimaloc.catapult.simulation;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/** {@link SimulationGate} providers for the real clients' unit tests. */
public final class SimulationGates {

    private SimulationGates() {}

    /** No simulation: the gate bean is absent. */
    public static ObjectProvider<SimulationGate> none() {
        return new StaticListableBeanFactory().getBeanProvider(SimulationGate.class);
    }

    public static ObjectProvider<SimulationGate> of(SimulationGate gate) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("simulationGate", gate);
        return factory.getBeanProvider(SimulationGate.class);
    }
}
