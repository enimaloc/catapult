package fr.enimaloc.catapult.admindata;

import fr.enimaloc.catapult.domain.ChatCommandDefinition;
import fr.enimaloc.catapult.domain.ChatCommandFallback;
import fr.enimaloc.catapult.domain.IgdbGameDetails;
import fr.enimaloc.catapult.domain.TwDefinition;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("mock-web")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:catapult_attr_classifier_it;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=KEY,VALUE"
})
class AttributeClassifierTest {

    @Autowired EntityManagerFactory emf;

    private <T> Attribute<?, ?> attr(Class<T> entityClass, String name) {
        EntityType<T> type = emf.getMetamodel().entity(entityClass);
        return type.getAttribute(name);
    }

    @Test
    void basicStringField_classifiedAsBasic() {
        assertThat(AttributeClassifier.classify(attr(TwDefinition.class, "label")))
            .isEqualTo(AttributeKind.BASIC);
    }

    @Test
    void manyToOne_classifiedAsSingularRelation() {
        assertThat(AttributeClassifier.classify(attr(ChatCommandDefinition.class, "user")))
            .isEqualTo(AttributeKind.SINGULAR_RELATION);
    }

    @Test
    void oneToMany_classifiedAsCollectionRelation() {
        assertThat(AttributeClassifier.classify(attr(ChatCommandDefinition.class, "fallbacks")))
            .isEqualTo(AttributeKind.COLLECTION_RELATION);
    }

    @Test
    void jsonConvertedMap_classifiedAsConverted() {
        assertThat(AttributeClassifier.classify(attr(IgdbGameDetails.class, "websites")))
            .isEqualTo(AttributeKind.CONVERTED);
    }

    @Test
    void manyToOneThatIsAlsoAnIdAttribute_classifiedAsSingularRelation() {
        assertThat(AttributeClassifier.classify(attr(ChatCommandFallback.class, "command")))
            .isEqualTo(AttributeKind.SINGULAR_RELATION);
    }

    @Test
    void label_usesFirstBasicStringAttribute_whenNoToStringOverride() {
        TwDefinition d = new TwDefinition();
        d.setId("flashing-lights");
        d.setLabel("Flashing lights");
        EntityType<TwDefinition> type = emf.getMetamodel().entity(TwDefinition.class);
        String label = AttributeClassifier.label(d, type);
        assertThat(label).contains("flashing-lights");
    }
}
