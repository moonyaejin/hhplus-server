package kr.hhplus.be.server.infrastructure.kafka;

import kr.hhplus.be.server.application.event.ReservationConfirmedEvent;
import kr.hhplus.be.server.infrastructure.kafka.message.ReservationEventMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationKafkaProducer {

    private static final String TOPIC = "reservation-confirmed";
    private static final String DATA_PLATFORM_TOPIC = "data-platform-events";  // 추가!

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 예약 확정 이벤트를 Kafka로 발행
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

    /**
     * 데이터 플랫폼으로 예약 이벤트 발행
     */
    public void sendToDataPlatform(ReservationEventMessage message) {
        String key = message.scheduleId().toString();

        kafkaTemplate.send(DATA_PLATFORM_TOPIC, key, message)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("[Kafka] 데이터 플랫폼 메시지 발행 성공 - topic: {}, key: {}, eventType: {}, partition: {}",
                                DATA_PLATFORM_TOPIC, key, message.eventType(), result.getRecordMetadata().partition());
                    } else {
                        log.error("[Kafka] 데이터 플랫폼 메시지 발행 실패 - topic: {}, key: {}, eventType: {}",
                                DATA_PLATFORM_TOPIC, key, message.eventType(), ex);
                    }
                });
    }
}