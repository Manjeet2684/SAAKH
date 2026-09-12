package in.manmeet.apexledger.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.manmeet.apexledger.api.TransferRequest;
import in.manmeet.apexledger.api.TransferResponse;
import in.manmeet.apexledger.support.DemoAccounts;
import in.manmeet.apexledger.transfer.TransferRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "saakh.outbox.enabled=true",
        "saakh.outbox.poll-delay-ms=3600000",
        "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OutboxPublisherTest {

    private static final String API_KEY = "local-dev-key";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.1"));

    @DynamicPropertySource
    static void kafkaBootstrap(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OutboxPublisher publisher;

    @Autowired
    OutboxEventRepository outboxEvents;

    @Autowired
    TransferRepository transfers;

    @Value("${spring.kafka.bootstrap-servers}")
    String bootstrapServers;

    @BeforeEach
    void drainUnpublishedEvents() {
        int claimed;
        do {
            claimed = publisher.publishBatch();
        } while (claimed > 0);
    }

    @Test
    void publishBatchSendsEventAndMarksPublished() throws Exception {
        TransferResponse posted = postTransfer(newKey("pub"), 100L);
        OutboxEvent unpublished = unpublishedFor(posted.transferId());
        assertNull(unpublished.getPublishedAt());

        int published = publisher.publishBatch();
        assertEquals(1, published);

        OutboxEvent after = outboxEvents.findById(unpublished.getEventId()).orElseThrow();
        assertNotNull(after.getPublishedAt());

        ConsumerRecord<String, String> record = awaitRecord(unpublished.getEventId());
        assertEquals("saakh.transfers", record.topic());
        assertEquals(posted.transferId().toString(), record.key());
        JsonNode body = objectMapper.readTree(record.value());
        assertEquals(unpublished.getEventId().toString(), body.path("eventId").asText());
        assertEquals(OutboxEvent.TRANSFER_COMPLETED, body.path("eventType").asText());
        assertEquals(1, body.path("schemaVersion").asInt());
        assertTrue(body.path("occurredAt").asText().length() > 0);
    }

    @Test
    void duplicateHttpIdempotencyDoesNotCreateSecondOutboxEvent() throws Exception {
        String key = newKey("idem");
        TransferResponse first = postTransfer(key, 50L);
        TransferResponse second = postTransfer(key, 50L);
        assertEquals(first.transferId(), second.transferId());
        assertEquals(1, transfers.countByIdempotencyKey(key));
        assertEquals(1, outboxEvents.countByAggregateIdAndEventType(first.transferId(), OutboxEvent.TRANSFER_COMPLETED));
        publisher.publishBatch();
        assertEquals(1, outboxEvents.countByAggregateIdAndEventType(first.transferId(), OutboxEvent.TRANSFER_COMPLETED));
    }

    @Test
    void concurrentPublishBatchClaimsOneRow() throws Exception {
        TransferResponse posted = postTransfer(newKey("conc"), 75L);
        UUID eventId = unpublishedFor(posted.transferId()).getEventId();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger processed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(15, TimeUnit.SECONDS);
                processed.addAndGet(publisher.publishBatch());
                return null;
            }));
        }
        assertTrue(ready.await(15, TimeUnit.SECONDS));
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        assertEquals(1, processed.get());
        assertNotNull(outboxEvents.findById(eventId).orElseThrow().getPublishedAt());
        assertEquals(1, countRecordsForEvent(eventId));
    }

    private TransferResponse postTransfer(String key, long amount) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", API_KEY)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(DemoAccounts.ALICE, DemoAccounts.BOB, amount, "INR"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TransferResponse.class);
    }

    private OutboxEvent unpublishedFor(UUID transferId) {
        List<OutboxEvent> events = outboxEvents.findByAggregateIdAndEventType(transferId, OutboxEvent.TRANSFER_COMPLETED);
        assertEquals(1, events.size());
        assertNull(events.getFirst().getPublishedAt());
        return events.getFirst();
    }

    private ConsumerRecord<String, String> awaitRecord(UUID eventId) {
        try (Consumer<String, String> consumer = consumer("phase3-success-" + UUID.randomUUID())) {
            consumer.subscribe(List.of("saakh.transfers"));
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(400))) {
                    if (eventId.toString().equals(objectMapper.readTree(record.value()).path("eventId").asText())) {
                        return record;
                    }
                }
            }
            throw new AssertionError("Kafka did not receive event " + eventId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int countRecordsForEvent(UUID eventId) {
        try (Consumer<String, String> consumer = consumer("phase3-count-" + UUID.randomUUID())) {
            consumer.subscribe(List.of("saakh.transfers"));
            int matches = 0;
            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(400))) {
                    if (eventId.toString().equals(objectMapper.readTree(record.value()).path("eventId").asText())) {
                        matches++;
                    }
                }
            }
            return matches;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Consumer<String, String> consumer(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        return new DefaultKafkaConsumerFactory<String, String>(props).createConsumer();
    }

    private static String newKey(String label) {
        return "p3-" + label + "-" + UUID.randomUUID();
    }
}
