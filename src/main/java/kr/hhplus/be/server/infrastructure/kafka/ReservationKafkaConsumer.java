package kr.hhplus.be.server.infrastructure.kafka;

import kr.hhplus.be.server.application.event.ReservationConfirmedEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ReservationKafkaConsumer {

    @KafkaListener(
            topics = "reservation-confirmed",
            groupId = "concert-reservation-group"
    )
    public void consume(ConsumerRecord<String, ReservationConfirmedEvent> record) {
        ReservationConfirmedEvent event = record.value();

        log.info("═══════════════════════════════════════");
        log.info("[Kafka Consumer] 메시지 수신");
        log.info("Topic: {}", record.topic());
        log.info("Partition: {}", record.partition());
        log.info("Offset: {}", record.offset());
        log.info("Key: {}", record.key());
        log.info("───────────────────────────────────────");
        log.info("예약 ID: {}", event.reservationId());
        log.info("사용자 ID: {}", event.userId());
        log.info("스케줄 ID: {}", event.scheduleId());
        log.info("좌석 번호: {}", event.seatNumber());
        log.info("가격: {}원", event.price());
        log.info("═══════════════════════════════════════");
    }
}