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

Create "transformer" which doubles the string

```
import io.smallrye.reactive.messaging.kafka.Record;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Outgoing;

import javax.enterprise.context.ApplicationScoped;

@JBossLog
@ApplicationScoped
public class SmartMeterValueDoublingProcessor {
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

Prepare `src/main/docker-compose/docker-compose.yml`
```
version: '3'
services:
  zookeeper:
    image: confluentinc/cp-zookeeper:latest
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    ports:
      - "2181:2181"

  kafka:
    image: confluentinc/cp-kafka:latest
    ports:
      - "9092:9092"
      - "9096:9096"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:29092,EXTERNAL://localhost:9092
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT
      KAFKA_INTER_BROKER_LISTENER_NAME: INTERNAL
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_JMX_PORT: 9096
    links:
      - zookeeper

  mosquitto:
    image: eclipse-mosquitto:1.6.15 # starting with 1.7 the remote connect is disabled by default
    ports:
      - "1883:1883"
```

Start docker compose
```
docker-compose up -d
```

Start listening to Kafka topic
```
docker exec -ti docker-compose_kafka_1 /bin/bash
kafka-console-consumer --bootstrap-server localhost:9092 --topic string-out
```

Publish message in Mosquitto
```
docker exec -ti docker-compose_mosquitto_1 /bin/sh
mosquitto_pub -t string-in -m hello
```

Tear down required docker
```
docker-compose down
```

## Devservices <a id="devservices"></a>
Would not it be nice not to have to write and bootstrap docker(-compose) manually?

Meet Devservices!

Create app
```
quarkus create app org.abratuhi.quarkus:demo-devservice-and-reactive-jpa --extensions=quarkus-resteasy-reactive,quarkus-resteasy-reactive-jackson,quarkus-hibernate-reactive-panache,quarkus-reactive-pg-client
cd demo-devservice-and-reactive-jpa
```

Add simple reactive endpoint serving entities from database (Postgres)
```

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;

@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Data
@Entity
public class Todo {
    @Id
    @GeneratedValue
    private Long id;

    @Column(name = "text")
    private String text;
}
```

```
import io.quarkus.hibernate.reactive.panache.PanacheRepository;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class TodoRepository implements PanacheRepository<Todo> {
}

```

```
import io.smallrye.mutiny.Uni;
import lombok.extern.jbosslog.JBossLog;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.util.List;
import java.util.stream.Collectors;

@JBossLog
@Path("/todo")
public class TodoResource {

    @Inject
    TodoRepository todoRepository;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<List<String>> getAll() {
       Uni<List<Todo>> todos = todoRepository.listAll();

       log.info("'Fetched' todos"); // <---- comes before Hibernate log

       return todos.map(it ->
                        it.stream()
                                .map(Todo::getText)
                                .collect(Collectors.toList())
                );
    }
}
```

Enable Devservices for Postgres in `application.properties`
```
quarkus.datasource.devservices.enabled=true
quarkus.datasource.devservices.username=postgres
quarkus.datasource.devservices.password=postgres
quarkus.datasource.devservices.port=5432

quarkus.datasource.db-kind=postgresql
quarkus.datasource.username=${quarkus.datasource.devservices.username}
quarkus.datasource.password=${quarkus.datasource.devservices.password}
quarkus.datasource.reactive.url=vertx-reactive:postgresql://localhost:${quarkus.datasource.devservices.port}/postgres

quarkus.hibernate-orm.database.generation=drop-and-create
quarkus.hibernate-orm.log.sql=true
quarkus.hibernate-orm.sql-load-script=import.sql
```

Provide initial seed `src/main/resources/import.sql`
```
INSERT INTO todo(id, text) VALUES (nextval('hibernate_sequence'), 'Finalize Quarkus Demo');
INSERT INTO todo(id, text) VALUES (nextval('hibernate_sequence'), 'Promote Quarkus Demo @ next Quarkus Con');
```

Start the application, note the output related to Docker:
```
mvn quarkus:dev
```

Check running docker containers:
```
docker ps -a
```

## Testing <a id="testing"></a>
Create app
```
quarkus create app org.abratuhi.quarkus:demo-testing
cd demo-testing
```

Add `SshClientFactory`
```
import lombok.SneakyThrows;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.enterprise.context.Dependent;

@Dependent
public class SshClientFactory {
    @ConfigProperty(name = "app.sftp.host")
    String sftpHost;

    @ConfigProperty(name = "app.sftp.port")
    Integer sftpPort;

    @ConfigProperty(name = "app.sftp.user")
    String sftpUser;

    @ConfigProperty(name = "app.sftp.password")
    String sftpPassword;

    @SneakyThrows
    public SSHClient getClient() {
        SSHClient client = new SSHClient();
        client.addHostKeyVerifier(new PromiscuousVerifier());
        client.connect(sftpHost, sftpPort);
        client.authPassword(sftpUser, sftpPassword);
        return client;
    }
}
```

Add necessary configuration to `application.properties`
```
app.sftp.host=localhost
app.sftp.port=2222
app.sftp.user=foo
app.sftp.password=bar
```

Update `pom.xml` to include sshj and testcontainers
```
<dependency>
    <groupId>com.hierynomus</groupId>
    <artifactId>sshj</artifactId>
    <version>0.34.0</version>
  </dependency>
<dependency>
  <groupId>org.assertj</groupId>
  <artifactId>assertj-core</artifactId>
  <version>3.23.1</version>
  <scope>test</scope>
  </dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers</artifactId>
    <version>1.17.4</version>
    <scope>test</scope>
  </dependency>
```

Create `SftpResource` which uploads a predefined file

```

import lombok.extern.jbosslog.JBossLog;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;

import javax.inject.Inject;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import java.net.URI;

@JBossLog
@Path("/sftp")
public class SftpResource {

  @Inject
  SshClientFactory sshClientFactory;

  @POST
  public Response create() {
    try (
        SSHClient sshClient = sshClientFactory.getClient();
        SFTPClient sftp = sshClient.newSFTPClient()
    ) {

      sftp.put("src/main/resources/.zshrc", "upload/.zshrc");

      return Response
          .created(URI.create("/"))
          .build();
    } catch (Exception e) {
      log.error(e);
      return Response.serverError().build();
    }
  }
}
```

Add the (empty) static file `.zshrc` to `src/main/resources`

Create the test first
```
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import lombok.extern.jbosslog.JBossLog;
import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.SFTPClient;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.Test;

import javax.inject.Inject;

import java.io.IOException;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

@JBossLog
@QuarkusTest
//@QuarkusTestResource(SftpTestResource.class)
//@TestProfile(SftpTestProfile.class)
public class SftpResourceTest {
  @Inject
  SshClientFactory sshClientFactory;

  @Test
  void fileIsUploaded() {
    try (
        SSHClient sshClient = sshClientFactory.getClient();
        SFTPClient sftp = sshClient.newSFTPClient()
    ) {
      // precondition/ pre-assert
      assertThat(sftp.ls("upload")).hasSize(0);

      // action
      given()
          .when().post("/sftp")
          .then()
          .statusCode(HttpStatus.SC_CREATED);

      // postcondition/ assert
      assertThat(sftp.ls("upload")).hasSize(1);
      assertThat(sftp.ls("upload").get(0).getName()).contains(".zshrc");

      log.info(sftp.ls("upload"));

    } catch (IOException e) {
      fail("Something wrong happened", e);
    }
  }
}
```

It fails, since no SFTP server is running.

Let's correct it by adding a `TestResource` to help test the app
```
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.util.Map;

public class SftpTestResource implements QuarkusTestResourceLifecycleManager {
  private GenericContainer<?> sftp;

  @Override
  public Map<String, String> start() {
    sftp = new GenericContainer<>("atmoz/sftp:alpine")
        .withExposedPorts(22)
        .withCommand("foo:bar:1001::upload")
        .waitingFor(Wait.forListeningPort());
    sftp.start();

    String mappedHost = "localhost";
    String mappedPort = String.valueOf(sftp.getMappedPort(22));
    String mappedUser = "foo";
    String mappedPassword = "bar";

    return Map.of(
        "app.sftp.host", mappedHost,
        "app.sftp.port", mappedPort,
        "app.sftp.user", mappedUser,
        "app.sftp.password", mappedPassword
    );
  }

  @Override
  public void stop() {
    sftp.stop();
  }
}
```

Annotate `SftpResourceTest` with `@QuarkusTestResource` and run it again

```
@QuarkusTestResource(SftpTestResource.class)
```

Now by the nature of `TestResource` it is started before every other `@QuarkusTest` is executed, let's demo on another test.

```
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


@QuarkusTest
public class DummyTest {
  @Test
  void doNothing() {
    assertThat(List.of()).isEmpty();
  }
}
```

To solve this (and some other) problems add `QuarkusTestProfile` to isolate test resource configurations

```
import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.List;

public class SftpTestProfile implements QuarkusTestProfile {
  @Override
  public List<TestResourceEntry> testResources() {
    return List.of(new TestResourceEntry(SftpTestResource.class));
  }

  @Override
  public boolean disableGlobalTestResources() {
    return true;
  }
}
```


## Putting it all together <a id="remote-dev"></a>
Remote dev allows for developing directly in the cloud!

Create the app using CLI:

```
quarkus create app org.abratuhi.quarkus:demo-remote-dev --extensions=quarkus-resteasy-reactive,container-image-jib,kubernetes
cd demo-remote-dev
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
kubectl port-forward svc/demo-remote-dev 9090:80
```

Test the endpoint
```
curl localhost:9090/hello -o -
```

Start the Quarkus application in remote dev mode:
```
mvn quarkus:remote-dev
```

Change greeting text in `GreetingResource` and test the endpoint again
```
curl localhost:9090/hello -o -
```




####




quarkus create app --stream=3.0

jbang --fresh upgrade-to-quarkus3@quarkusio


Lombok dependency
```
<dependency>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
      <version>1.18.24</version>
    </dependency>
```