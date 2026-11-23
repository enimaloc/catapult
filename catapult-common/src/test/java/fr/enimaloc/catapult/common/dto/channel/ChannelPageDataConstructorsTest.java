package fr.enimaloc.catapult.common.dto.channel;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Every publisher lot adds a card to the page and keeps the former constructors so that their callers stay unchanged:
 * each of them must still build a page, the cards it does not know left null.
 */
class ChannelPageDataConstructorsTest {

    @Test
    void everyConstructorBuildsAPage() throws Exception {
        Constructor<?>[] constructors = Arrays.stream(ChannelPageData.class.getConstructors())
                .filter(c -> Modifier.isPublic(c.getModifiers()))
                .toArray(Constructor<?>[]::new);
        assertThat(constructors).hasSizeGreaterThan(1);

        for (Constructor<?> constructor : constructors) {
            Object[] args = Arrays.stream(constructor.getParameterTypes()).map(ChannelPageDataConstructorsTest::zero)
                    .toArray();

            ChannelPageData page = (ChannelPageData) constructor.newInstance(args);

            assertThat(page.channelUsername()).as(constructor.toString()).isNull();
        }
    }

    private static Object zero(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
