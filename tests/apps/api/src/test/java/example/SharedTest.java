package example;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SharedTest {
    @Test
    void includesSharedSources() {
        assertEquals("hello", Shared.greeting());
    }
}
