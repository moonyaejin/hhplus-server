package kr.hhplus.be.server.infrastructure.kafka.producer;

import kr.hhplus.be.server.infrastructure.kafka.message.PaymentRequestMessage;
import kr.hhplus.be.server.infrastructure.kafka.message.PaymentResultMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 결제 관련 Kafka 메시지 Producer
 *
 * 토픽:
 * - payment-requests: 결제 요청 (Key: userId)
 * - payment-results: 결제 결과 (Key: reservationId)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentKafkaProducer {

    private static final String PAYMENT_REQUESTS_TOPIC = "payment-requests";
    private static final String PAYMENT_RESULTS_TOPIC = "payment-results";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 결제 요청 메시지 발행
     *
     * @param message 결제 요청 메시지
     * Key: userId (같은 사용자의 결제 요청은 순서 보장)
     */
    public void sendPaymentRequest(PaymentRequestMessage message) {
        log.info("결제 요청 발행 시작 - reservationId: {}, userId: {}, amount: {}",
                message.reservationId(), message.userId(), message.amount());

        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(
                PAYMENT_REQUESTS_TOPIC,
                message.userId(),  // Key: userId
                message
        );

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("결제 요청 발행 실패 - reservationId: {}, error: {}",
                        message.reservationId(), ex.getMessage(), ex);
            } else {
                log.info("결제 요청 발행 완료 - reservationId: {}, partition: {}, offset: {}",
                        message.reservationId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }

    /**
     * 결제 결과 메시지 발행
     *
     * @param message 결제 결과 메시지
     * Key: reservationId (같은 예약의 결과는 순서 보장)
     */
    public void sendPaymentResult(PaymentResultMessage message) {
        log.info("결제 결과 발행 시작 - reservationId: {}, status: {}",
                message.reservationId(), message.status());

        CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(
                PAYMENT_RESULTS_TOPIC,
                message.reservationId(),  // Key: reservationId
                message
        );

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("결제 결과 발행 실패 - reservationId: {}, error: {}",
                        message.reservationId(), ex.getMessage(), ex);
            } else {
                log.info("결제 결과 발행 완료 - reservationId: {}, partition: {}, offset: {}",
                        message.reservationId(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}