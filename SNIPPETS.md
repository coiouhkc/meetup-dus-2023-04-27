# Table of contents
1. [Environment setup](#env)
2. [Reactive messaging](#reactive-messaging)
3. [Devservices with reactive JPA](#devservices)
4. [Testing](#testing)
5. [Putting it all together = Remote-Dev](#remote-dev)

## Environment setup <a id="env"></a>

Use `sdkman`!

```
sdk use java 17.0.4.1-zulu
sdk use maven 3.8.7
```


## Reactive messaging <a id="reactive-messaging"></a>
SmallRye reactive messaging allows for seamlessly connecting sources and sinks of different types/ techs!

Create app
```
quarkus create app org.abratuhi.quarkus:demo-reactive-messaging --extensions=quarkus-smallrye-reactive-messaging-kafka,quarkus-smallrye-reactive-messaging-mqtt
cd demo-reactive-messaging
```

Delete generated classes
```
find . -iname 'MyReactiveMessagingApplication*' -exec rm -fr {} \;
```

Create "transformer" which doubles the string

```
import io.smallrye.reactive.messaging.kafka.Record;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class DoubleDownProcessor {
    @Incoming("string-in")
    @Outgoing("string-out")
    public Record<String, String> x2(byte[] bytes) {
        String str = new String(bytes);

        return Record.of(str, str + str);
    }
}
```

Configure the connectors in `application.properties`
```
mp.messaging.incoming.string-in.connector=smallrye-mqtt
mp.messaging.incoming.string-in.topic=string-in
mp.messaging.incoming.string-in.host=localhost
mp.messaging.incoming.string-in.port=1883
mp.messaging.incoming.string-in.auto-generated-client-id=true

kafka.bootstrap.servers=localhost:9092

mp.messaging.outgoing.string-out.connector=smallrye-kafka
mp.messaging.outgoing.string-out.topic=string-out

quarkus.devservices.enabled=false
```

Start docker compose
```
docker-compose up -d
```

Start listening to Kafka topic
```
docker exec -ti docker_kafka_1 /bin/bash
kafka-console-consumer --bootstrap-server localhost:9092 --topic string-out
```

Publish message in Mosquitto
```
docker exec -ti docker_mosquitto_1 /bin/sh
mosquitto_pub -t string-in -m hello
```

Stop the app
```
CTRL+C
```

Tear down required docker
```
docker-compose down
```

## Devservices <a id="devservices"></a>
Would not it be nice not to have to write and bootstrap docker(-compose) manually?

Meet Devservices!

Comment-out/ remove 
```
#mp.messaging.incoming.string-in.host=localhost
#mp.messaging.incoming.string-in.port=1883

#kafka.bootstrap.servers=localhost:9092

#quarkus.devservices.enabled=false
```

Add `System.out.println("Received" + str);` to processor/transformer class, since redpanda devservice does not seem to have `kafka-console-consumer`

## Testing <a id="testing"></a>
Add required dependencies
```
<dependency>
  <groupId>io.smallrye.reactive</groupId>
  <artifactId>smallrye-reactive-messaging-in-memory</artifactId>
  <scope>test</scope>
</dependency>
```

Create test
```

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

        InMemorySink<Record> stringOut = connector.sink("string-out");
        String actual = (String) stringOut.received().get(0).getPayload().value();
        assertEquals("hellohello", actual);
    }
}
```

Adjust `application.properties` to use in-memory connector during tests
```
%test.mp.messaging.incoming.string-in.connector=smallrye-in-memory
%test.mp.messaging.outgoing.string-out.connector=smallrye-in-memory
%test.quarkus.devservices.enabled=false
```

Run the test.

## Putting it all together <a id="remote-dev"></a>
Remote dev allows for developing directly in the cloud!


Install Kafka (microk8s using Strimzi Operator)
```
microk8s helm install strimzi/strimzi-kafka-operator --generate-name
kubectl apply -f https://strimzi.io/examples/latest/kafka/kafka-persistent-single.yaml
```

Install mosquitto
```
kubectl run mosquitto --image=eclipse-mosquitto:1.6.15 --expose=true --port=1883
```

Add necessary extensions to the application:
```
quarkus ext add quarkus-resteasy
quarkus ext add container-image-jib
quarkus ext add kubernetes
```

Adjust `application.properties` to push to docker registry of local kubernetes (`microk8s status | grep registry`)

```
quarkus.container-image.push=true
quarkus.container-image.insecure=true
quarkus.container-image.registry=localhost:32000
```

Adjust `application.properties` allow remote dev

```
quarkus.package.type=mutable-jar
quarkus.live-reload.password=changeit
quarkus.live-reload.url=http://localhost:9090
```

Adjust `application.properties` to use Kafka and Mosquitto from Kubernetes

```
mp.messaging.incoming.string-in.connector=smallrye-mqtt
mp.messaging.incoming.string-in.topic=string-in
mp.messaging.incoming.string-in.host=mosquitto
mp.messaging.incoming.string-in.port=1883
mp.messaging.incoming.string-in.auto-generated-client-id=true

kafka.bootstrap.servers=my-cluster-kafka-bootstrap:9092

mp.messaging.outgoing.string-out.connector=smallrye-kafka
mp.messaging.outgoing.string-out.topic=string-out
```

Build the image and generate kubernetes deployment descriptors:
```
mvn clean package
```

Adjust `target/kubernetes/kubernetes.yml` to start container pod in dev mode:
```
QUARKUS_LAUNCH_DEVMODE=true
```

```
- env:
    - name: QUARKUS_LAUNCH_DEVMODE
      value: "true"
```

Deploy
```
kubectl create -f target/kubernetes/kubernetes.yml
```

Configure port-forwarding in separate tab/pane
```
kubectl port-forward svc/demo-reactive-messaging 9090:8080
```


Listen to Kafka

```
kubectl run kafka-consumer -ti --image=quay.io/strimzi/kafka:0.34.0-kafka-3.4.0 --rm=true --restart=Never -- bin/kafka-console-consumer.sh --bootstrap-server my-cluster-kafka-bootstrap:9092 --topic string-out
```

Open mosquitto
```
kubectl exec -ti mosquitto -- /bin/sh
```

Send messages
```
mosquitto_pub -t string-in -m hello
```

Start quarkus in remote-dev mode
```
mvn quarkus:remote-dev
```

Adjust code to triple the messages and publish a couple of new messages using `mosquitto_pub`.
