package fr.enimaloc.catapult.experiment.provider;

import fr.enimaloc.catapult.config.ExperimentProviderProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveProviderHolderTest {

    @Test
    void internalProvider_getReturnsNull_isInternalTrue() {
        var props = new ExperimentProviderProperties();
        var holder = new ActiveProviderHolder(props);

        assertThat(holder.get()).isNull();
        assertThat(holder.isInternal()).isTrue();
    }

    @Test
    void unknownProvider_getReturnsNull_isInternalFalse() {
        var props = new ExperimentProviderProperties();
        props.setProvider("custom");
        var holder = new ActiveProviderHolder(props);

        assertThat(holder.get()).isNull();
        assertThat(holder.isInternal()).isFalse();
    }

    @Test
    void growthbookProvider_getReturnsProvider() {
        var props = new ExperimentProviderProperties();
        props.setProvider("growthbook");
        var holder = new ActiveProviderHolder(props);

        assertThat(holder.get()).isNotNull();
        assertThat(holder.isInternal()).isFalse();
    }
}
