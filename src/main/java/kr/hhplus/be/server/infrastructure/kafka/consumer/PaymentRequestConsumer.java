package kr.hhplus.be.server.infrastructure.kafka.consumer;

import kr.hhplus.be.server.application.port.in.PaymentUseCase;
import kr.hhplus.be.server.domain.payment.InsufficientBalanceException;
import kr.hhplus.be.server.infrastructure.kafka.message.PaymentRequestMessage;
import kr.hhplus.be.server.infrastructure.kafka.message.PaymentResultMessage;
import kr.hhplus.be.server.infrastructure.kafka.producer.PaymentKafkaProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 결제 요청 Consumer
 *
 * Topic: payment-requests
 * Group: payment-processor
 *
 * 역할:
 * 1. 결제 요청 메시지 수신
 * 2. PaymentUseCase를 통해 결제 처리
 * 3. 결제 결과를 payment-results 토픽으로 발행
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentRequestConsumer {

    private final PaymentUseCase paymentUseCase;
    private final PaymentKafkaProducer paymentKafkaProducer;

    @KafkaListener(
            topics = "payment-requests",
            groupId = "payment-processor",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void handlePaymentRequest(PaymentRequestMessage message, Acknowledgment ack) {
        log.info("═══════════════════════════════════════════════════");
        log.info("결제 요청 수신");
        log.info("예약 ID: {}", message.reservationId());
        log.info("사용자 ID: {}", message.userId());
        log.info("결제 금액: {:,}원", message.amount());
        log.info("═══════════════════════════════════════════════════");

        try {
            // 1. 결제 처리
            PaymentUseCase.PaymentCommand paymentCommand = new PaymentUseCase.PaymentCommand(
                    message.userId(),
                    message.amount(),
                    message.idempotencyKey()
            );

            PaymentUseCase.BalanceResult result = paymentUseCase.pay(paymentCommand);

            // 2. 결제 성공 결과 발행
            PaymentResultMessage successResult = PaymentResultMessage.success(
                    message.reservationId(),
                    message.userId(),
                    result.balance()
            );
            paymentKafkaProducer.sendPaymentResult(successResult);

            log.info("결제 성공 - reservationId: {}, 잔액: {:,}원",
                    message.reservationId(), result.balance());

            // 3. 커밋
            ack.acknowledge();

        } catch (InsufficientBalanceException e) {
            // 잔액 부족 - 비즈니스 실패 (재시도 불필요)
            log.warn("잔액 부족 - reservationId: {}, userId: {}, error: {}",
                    message.reservationId(), message.userId(), e.getMessage());

            PaymentResultMessage failResult = PaymentResultMessage.insufficientBalance(
                    message.reservationId(),
                    message.userId(),
                    e.getMessage()
            );
            paymentKafkaProducer.sendPaymentResult(failResult);

            // 비즈니스 실패는 커밋 (재시도해도 동일)
            ack.acknowledge();

        } catch (Exception e) {
            // 시스템 오류 - 재시도 필요
            log.error("결제 처리 중 시스템 오류 - reservationId: {}, error: {}",
                    message.reservationId(), e.getMessage(), e);

            // 커밋하지 않음 → 재시도됨
            // 단, 무한 재시도 방지를 위해 DLQ 설정 필요
        }
    }
}