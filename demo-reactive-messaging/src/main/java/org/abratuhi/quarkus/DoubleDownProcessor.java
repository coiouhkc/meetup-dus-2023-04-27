package org.abratuhi.quarkus;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;

import io.smallrye.reactive.messaging.kafka.Record;

public class DoubleDownProcessor {
    @Incoming("string-in")
    @Outgoing("string-out")
    public Record<String, String> x2(byte[] bytes) {
        String str = new String(bytes);
        System.out.println("Received:" + str);
        return Record.of(str, str + str);
    }
}
