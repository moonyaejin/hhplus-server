package kr.hhplus.be.server.infrastructure.kafka;

import kr.hhplus.be.server.application.event.ReservationConfirmedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationKafkaProducer {

    private static final String TOPIC = "reservation-confirmed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 예약 확정 이벤트를 Kafka로 발행
     * Key: scheduleId (같은 콘서트 스케줄 → 같은 파티션 → 순서 보장)
     */
    public void send(ReservationConfirmedEvent event) {
        String key = event.scheduleId().toString();

        kafkaTemplate.send(TOPIC, key, event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("[Kafka] 메시지 발행 성공 - topic: {}, key: {}, partition: {}",
                                TOPIC, key, result.getRecordMetadata().partition());
                    } else {
                        log.error("[Kafka] 메시지 발행 실패 - topic: {}, key: {}", TOPIC, key, ex);
                    }
                });
    }
}