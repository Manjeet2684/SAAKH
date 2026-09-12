package in.manmeet.apexledger.outbox;

import in.manmeet.apexledger.api.TransferRequest;
import in.manmeet.apexledger.api.TransferResponse;
import in.manmeet.apexledger.support.DemoAccounts;
import in.manmeet.apexledger.transfer.TransferRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "saakh.outbox.enabled=true",
        "saakh.outbox.batch-size=1",
        "saakh.outbox.poll-delay-ms=3600000",
        "spring.task.scheduling.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OutboxPublisherFailureTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

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

    @MockBean
    KafkaEventPublisher kafkaEventPublisher;

    @BeforeEach
    void drainUnpublishedEvents() {
        doNothing().when(kafkaEventPublisher).publish(any());
        int claimed;
        do {
            claimed = publisher.publishBatch();
        } while (claimed > 0);
        reset(kafkaEventPublisher);
    }

    @Test
    void kafkaFailureLeavesEventUnpublishedAndRetryable() throws Exception {
        TransferResponse posted = postTransfer(newKey("fail"), 40L);
        UUID eventId = unpublished(posted.transferId()).getEventId();
        doThrow(new IllegalStateException("kafka down")).when(kafkaEventPublisher).publish(any());

        assertThrows(IllegalStateException.class, publisher::publishBatch);

        OutboxEvent after = outboxEvents.findById(eventId).orElseThrow();
        assertNull(after.getPublishedAt());
        assertEquals(1, countTransfersFor(posted.transferId()));
        assertEquals(1, outboxEvents.countByAggregateIdAndEventType(posted.transferId(), OutboxEvent.TRANSFER_COMPLETED));
    }

    @Test
    void retrySucceedsAfterPreviousFailure() throws Exception {
        TransferResponse posted = postTransfer(newKey("retry"), 30L);
        UUID eventId = unpublished(posted.transferId()).getEventId();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("first send fails");
            }
            return null;
        }).when(kafkaEventPublisher).publish(any());

        assertThrows(IllegalStateException.class, publisher::publishBatch);
        assertNull(outboxEvents.findById(eventId).orElseThrow().getPublishedAt());

        assertEquals(1, publisher.publishBatch());
        assertNotNull(outboxEvents.findById(eventId).orElseThrow().getPublishedAt());
        assertEquals(1, countTransfersFor(posted.transferId()));
        assertEquals(1, outboxEvents.countByAggregateIdAndEventType(posted.transferId(), OutboxEvent.TRANSFER_COMPLETED));
    }

    @Test
    void crashAfterKafkaAckCanRepublishSameEventId() throws Exception {
        TransferResponse posted = postTransfer(newKey("crash"), 20L);
        UUID eventId = unpublished(posted.transferId()).getEventId();
        String payload = unpublished(posted.transferId()).getPayload().toString();
        List<UUID> sentIds = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            OutboxEvent event = invocation.getArgument(0);
            sentIds.add(event.getEventId());
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("crash after kafka ack");
            }
            return null;
        }).when(kafkaEventPublisher).publish(any());

        assertThrows(IllegalStateException.class, publisher::publishBatch);
        assertNull(outboxEvents.findById(eventId).orElseThrow().getPublishedAt());

        assertEquals(1, publisher.publishBatch());
        assertNotNull(outboxEvents.findById(eventId).orElseThrow().getPublishedAt());
        assertEquals(2, sentIds.size());
        assertEquals(eventId, sentIds.get(0));
        assertEquals(eventId, sentIds.get(1));
        assertEquals(payload, outboxEvents.findById(eventId).orElseThrow().getPayload().toString());
        assertEquals(1, countTransfersFor(posted.transferId()));
    }

    private long countTransfersFor(UUID transferId) {
        return transfers.findById(transferId).isPresent() ? 1 : 0;
    }

    private OutboxEvent unpublished(UUID transferId) {
        List<OutboxEvent> events = outboxEvents.findByAggregateIdAndEventType(transferId, OutboxEvent.TRANSFER_COMPLETED);
        assertEquals(1, events.size());
        return events.getFirst();
    }

    private TransferResponse postTransfer(String key, long amount) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/transfers")
                        .header("X-API-Key", "local-dev-key")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(DemoAccounts.ALICE, DemoAccounts.BOB, amount, "INR"))))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), TransferResponse.class);
    }

    private static String newKey(String label) {
        return "p3-" + label + "-" + UUID.randomUUID();
    }
}
