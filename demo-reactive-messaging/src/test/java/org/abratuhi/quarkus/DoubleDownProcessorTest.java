package org.abratuhi.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.smallrye.reactive.messaging.kafka.Record;
import io.smallrye.reactive.messaging.memory.InMemoryConnector;
import io.smallrye.reactive.messaging.memory.InMemorySink;
import io.smallrye.reactive.messaging.memory.InMemorySource;
import jakarta.enterprise.inject.Any;
import jakarta.inject.Inject;

@QuarkusTest
public class DoubleDownProcessorTest {
    @Inject @Any
    InMemoryConnector connector;

    @Test
    void doubleDownOk() {
        InMemorySource<byte[]> stringIn = connector.source("string-in");
        stringIn.send("hello".getBytes());

        InMemorySink<Record<String,String>> stringOut = connector.sink("string-out");
        assertEquals("hellohello", stringOut.received().get(0).getPayload().value());
    }

}
