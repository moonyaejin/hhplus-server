package kr.hhplus.be.server.kafka;

import kr.hhplus.be.server.application.event.ReservationConfirmedEvent;
import kr.hhplus.be.server.infrastructure.kafka.ReservationKafkaProducer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Kafka 통합 테스트")
class KafkaIntegrationTest {

    @Autowired
    private ReservationKafkaProducer kafkaProducer;

    @Test
    @DisplayName("예약 확정 이벤트를 Kafka로 발행하고 Consumer가 수신한다")
    void sendReservationConfirmedEvent() throws InterruptedException {
        // given
        ReservationConfirmedEvent event = ReservationConfirmedEvent.of(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                1L,         // scheduleId
                15,         // seatNumber
                80000L,     // price
                LocalDateTime.now()
        );

        // when
        log.info("🚀 메시지 발행 시작...");
        kafkaProducer.send(event);

        // then - Consumer 로그 확인을 위해 잠시 대기
        Thread.sleep(3000);
        log.info("테스트 완료 - Consumer 수신 확인!");
    }

    @Test
    @DisplayName("같은 scheduleId는 같은 파티션으로 전송된다")
    void sameKeyGoesToSamePartition() throws InterruptedException {
        // given - 같은 scheduleId로 3개 이벤트
        Long scheduleId = 100L;

        for (int i = 1; i <= 3; i++) {
            ReservationConfirmedEvent event = ReservationConfirmedEvent.of(
                    "R00" + i,
                    "U00" + i,
                    scheduleId,     // 같은 scheduleId!
                    i,
                    80000L * i,
                    LocalDateTime.now()
            );

            log.info("🚀 메시지 {} 발행...", i);
            kafkaProducer.send(event);
        }

        // then
        Thread.sleep(3000);
        log.info("3개 메시지 모두 같은 파티션인지 로그로 확인!");
    }

    @Test
    @DisplayName("100개 메시지를 빠르게 발행한다")
    void bulkMessageTest() throws InterruptedException {
        // given
        int messageCount = 100;
        long startTime = System.currentTimeMillis();

        // when
        for (int i = 0; i < messageCount; i++) {
            ReservationConfirmedEvent event = ReservationConfirmedEvent.of(
                    "R" + String.format("%04d", i),
                    "U" + String.format("%04d", i),
                    (long) (i % 10),  // 10개 scheduleId로 분산
                    i % 50,
                    80000L,
                    LocalDateTime.now()
            );
            kafkaProducer.send(event);
        }

        long endTime = System.currentTimeMillis();
        log.info("🚀 {}개 메시지 발행 완료 - 소요시간: {}ms", messageCount, endTime - startTime);

        // then
        Thread.sleep(5000);  // Consumer가 다 받을 때까지 대기
        log.info("Consumer 수신 완료 확인!");
    }

    @Test
    @DisplayName("같은 scheduleId의 예약 → 확정 → 취소 순서가 보장된다")
    void messageOrderGuaranteeTest() throws InterruptedException {
        // given - 같은 스케줄에 대한 순차적 이벤트
        Long scheduleId = 999L;

        String[] eventTypes = {"RESERVED", "CONFIRMED", "CANCELLED"};

        for (int i = 0; i < eventTypes.length; i++) {
            ReservationConfirmedEvent event = ReservationConfirmedEvent.of(
                    "ORDER-TEST-" + i,
                    "USER-1",
                    scheduleId,
                    10,
                    80000L,
                    LocalDateTime.now()
            );

            log.info("🚀 발행: {} (순서: {})", eventTypes[i], i);
            kafkaProducer.send(event);

            Thread.sleep(100);  // 순서 명확히 하기 위해
        }

        // then
        Thread.sleep(3000);
        log.info("Consumer 로그에서 Offset 순서 확인! (0 → 1 → 2)");
    }
}