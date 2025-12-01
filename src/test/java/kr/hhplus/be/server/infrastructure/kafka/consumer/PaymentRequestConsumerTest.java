package kr.hhplus.be.server.infrastructure.kafka.consumer;

import kr.hhplus.be.server.application.port.in.PaymentUseCase;
import kr.hhplus.be.server.domain.payment.InsufficientBalanceException;
import kr.hhplus.be.server.infrastructure.kafka.message.PaymentRequestMessage;
import kr.hhplus.be.server.infrastructure.kafka.message.PaymentResultMessage;
import kr.hhplus.be.server.infrastructure.kafka.message.PaymentStatus;
import kr.hhplus.be.server.infrastructure.kafka.producer.PaymentKafkaProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentRequestConsumer 단위 테스트")
class PaymentRequestConsumerTest {

    @Mock
    private PaymentUseCase paymentUseCase;

    @Mock
    private PaymentKafkaProducer paymentKafkaProducer;

    @Mock
    private Acknowledgment acknowledgment;

    @InjectMocks
    private PaymentRequestConsumer consumer;

    private PaymentRequestMessage testMessage;

    private static final String RESERVATION_ID = "test-reservation-id";
    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final Long AMOUNT = 80_000L;
    private static final String IDEMPOTENCY_KEY = "test-idempotency-key";

    @BeforeEach
    void setUp() {
        testMessage = new PaymentRequestMessage(
                RESERVATION_ID,
                USER_ID,
                AMOUNT,
                IDEMPOTENCY_KEY,
                1L,
                15,
                LocalDateTime.now()
        );
    }

    @Nested
    @DisplayName("결제 성공 시")
    class WhenPaymentSucceeds {

        @Test
        @DisplayName("성공 결과 메시지를 발행하고 커밋한다")
        void shouldPublishSuccessResultAndAcknowledge() {
            // given
            long remainingBalance = 20_000L;
            when(paymentUseCase.pay(any())).thenReturn(
                    new PaymentUseCase.BalanceResult(remainingBalance)
            );

            // when
            consumer.handlePaymentRequest(testMessage, acknowledgment);

            // then
            // 1. 결제 요청 확인
            ArgumentCaptor<PaymentUseCase.PaymentCommand> paymentCaptor =
                    ArgumentCaptor.forClass(PaymentUseCase.PaymentCommand.class);
            verify(paymentUseCase).pay(paymentCaptor.capture());

            PaymentUseCase.PaymentCommand capturedCommand = paymentCaptor.getValue();
            assertThat(capturedCommand.userId()).isEqualTo(USER_ID);
            assertThat(capturedCommand.amount()).isEqualTo(AMOUNT);
            assertThat(capturedCommand.idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);

            // 2. 성공 결과 발행 확인
            ArgumentCaptor<PaymentResultMessage> resultCaptor =
                    ArgumentCaptor.forClass(PaymentResultMessage.class);
            verify(paymentKafkaProducer).sendPaymentResult(resultCaptor.capture());

            PaymentResultMessage capturedResult = resultCaptor.getValue();
            assertThat(capturedResult.reservationId()).isEqualTo(RESERVATION_ID);
            assertThat(capturedResult.userId()).isEqualTo(USER_ID);
            assertThat(capturedResult.status()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(capturedResult.balance()).isEqualTo(remainingBalance);
            assertThat(capturedResult.failReason()).isNull();

            // 3. 커밋 확인
            verify(acknowledgment).acknowledge();
        }
    }

    @Nested
    @DisplayName("잔액 부족 시")
    class WhenInsufficientBalance {

        @Test
        @DisplayName("실패 결과 메시지를 발행하고 커밋한다 (재시도 불필요)")
        void shouldPublishFailureResultAndAcknowledge() {
            // given
            String errorMessage = "잔액이 부족합니다. 현재 잔액: 10,000원, 필요 금액: 80,000원";
            when(paymentUseCase.pay(any())).thenThrow(
                    new InsufficientBalanceException(errorMessage)
            );

            // when
            consumer.handlePaymentRequest(testMessage, acknowledgment);

            // then
            // 1. 실패 결과 발행 확인
            ArgumentCaptor<PaymentResultMessage> resultCaptor =
                    ArgumentCaptor.forClass(PaymentResultMessage.class);
            verify(paymentKafkaProducer).sendPaymentResult(resultCaptor.capture());

            PaymentResultMessage capturedResult = resultCaptor.getValue();
            assertThat(capturedResult.reservationId()).isEqualTo(RESERVATION_ID);
            assertThat(capturedResult.userId()).isEqualTo(USER_ID);
            assertThat(capturedResult.status()).isEqualTo(PaymentStatus.INSUFFICIENT_BALANCE);
            assertThat(capturedResult.balance()).isNull();
            assertThat(capturedResult.failReason()).isEqualTo(errorMessage);

            // 2. 커밋 확인 (비즈니스 실패는 재시도해도 동일하므로 커밋)
            verify(acknowledgment).acknowledge();
        }
    }

    @Nested
    @DisplayName("시스템 오류 발생 시")
    class WhenSystemError {

        @Test
        @DisplayName("결과 메시지를 발행하지 않고 커밋하지 않는다 (재시도 필요)")
        void shouldNotPublishResultAndNotAcknowledge() {
            // given
            when(paymentUseCase.pay(any())).thenThrow(
                    new RuntimeException("DB 연결 실패")
            );

            // when
            consumer.handlePaymentRequest(testMessage, acknowledgment);

            // then
            // 1. 결과 발행하지 않음
            verify(paymentKafkaProducer, never()).sendPaymentResult(any());

            // 2. 커밋하지 않음 (Kafka가 재시도)
            verify(acknowledgment, never()).acknowledge();
        }
    }

    @Nested
    @DisplayName("멱등성 검증")
    class IdempotencyValidation {

        @Test
        @DisplayName("동일한 idempotencyKey로 중복 요청 시 PaymentUseCase가 처리한다")
        void shouldDelegateIdempotencyToPaymentUseCase() {
            // given
            when(paymentUseCase.pay(any())).thenReturn(
                    new PaymentUseCase.BalanceResult(20_000L)
            );

            // when
            consumer.handlePaymentRequest(testMessage, acknowledgment);

            // then
            ArgumentCaptor<PaymentUseCase.PaymentCommand> captor =
                    ArgumentCaptor.forClass(PaymentUseCase.PaymentCommand.class);
            verify(paymentUseCase).pay(captor.capture());

            // idempotencyKey가 전달되는지 확인
            assertThat(captor.getValue().idempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
        }
    }
}