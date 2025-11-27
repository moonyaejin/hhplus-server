package kr.hhplus.be.server.kafka;

import kr.hhplus.be.server.infrastructure.kafka.ReservationKafkaProducer;
import kr.hhplus.be.server.infrastructure.kafka.message.ReservationEventMessage;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("데이터 플랫폼 Kafka 전송 테스트")
class DataPlatformKafkaTest {

    @Autowired
    private ReservationKafkaProducer kafkaProducer;

    // 메시지 ID별로 수신 여부 추적
    private static ConcurrentHashMap<String, ReservationEventMessage> receivedMessages = new ConcurrentHashMap<>();
    private static CountDownLatch latch;

    @BeforeEach
    void setUp() {
        receivedMessages.clear();
    }

    // 테스트용 Consumer - 메시지 ID로 구분
    @KafkaListener(topics = "data-platform-events", groupId = "test-consumer-#{T(java.util.UUID).randomUUID()}")
    public void testConsumer(ReservationEventMessage message) {
        log.info("[테스트 Consumer] 메시지 수신: {}", message.reservationId());
        receivedMessages.put(message.reservationId(), message);
        if (latch != null) {
            latch.countDown();
        }
    }

    @Test
    @DisplayName("예약 확정 메시지 발행 → Consumer 수신 검증")
    void sendConfirmedEvent_shouldBeReceivedByConsumer() throws InterruptedException {
        // given
        latch = new CountDownLatch(1);
        String reservationId = "TEST-CONFIRMED-" + UUID.randomUUID();

        ReservationEventMessage message = ReservationEventMessage.confirmed(
                reservationId,
                "USER-001",
                1L,
                15,
                80000L,
                LocalDateTime.now()
        );

        // when
        log.info("메시지 발행: {}", reservationId);
        kafkaProducer.sendToDataPlatform(message);

        // then - 10초 내 수신 확인
        boolean received = latch.await(10, TimeUnit.SECONDS);

        assertThat(received).isTrue();
        assertThat(receivedMessages.containsKey(reservationId)).isTrue();

        ReservationEventMessage receivedMessage = receivedMessages.get(reservationId);
        assertThat(receivedMessage.eventType()).isEqualTo("CONFIRMED");
        assertThat(receivedMessage.price()).isEqualTo(80000L);

        log.info("검증 완료 - 메시지 정상 수신!");
    }

    @Test
    @DisplayName("예약 취소 메시지 발행 → Consumer 수신 검증")
    void sendCancelledEvent_shouldBeReceivedByConsumer() throws InterruptedException {
        // given
        latch = new CountDownLatch(1);
        String reservationId = "TEST-CANCELLED-" + UUID.randomUUID();

        ReservationEventMessage message = ReservationEventMessage.cancelled(
                reservationId,
                "USER-002",
                2L,
                20,
                100000L,
                LocalDateTime.now()
        );

        // when
        log.info("취소 메시지 발행: {}", reservationId);
        kafkaProducer.sendToDataPlatform(message);

        // then
        boolean received = latch.await(10, TimeUnit.SECONDS);

        assertThat(received).isTrue();
        assertThat(receivedMessages.containsKey(reservationId)).isTrue();
        assertThat(receivedMessages.get(reservationId).eventType()).isEqualTo("CANCELLED");

        log.info("취소 메시지 검증 완료!");
    }

    @Test
    @DisplayName("여러 메시지를 발행하면 모두 수신된다")
    void multipleMessages_shouldAllBeReceived() throws InterruptedException {
        // given
        int messageCount = 5;
        latch = new CountDownLatch(messageCount);

        // when
        for (int i = 0; i < messageCount; i++) {
            String reservationId = "MULTI-" + i + "-" + UUID.randomUUID();
            ReservationEventMessage message = ReservationEventMessage.confirmed(
                    reservationId,
                    "USER-" + i,
                    (long) i,
                    i + 1,
                    80000L * (i + 1),
                    LocalDateTime.now()
            );

            log.info("메시지 {} 발행: {}", i, reservationId);
            kafkaProducer.sendToDataPlatform(message);
        }

        // then
        boolean allReceived = latch.await(15, TimeUnit.SECONDS);

        assertThat(allReceived).isTrue();
        log.info("{}개 메시지 모두 수신 완료!", messageCount);
    }
}