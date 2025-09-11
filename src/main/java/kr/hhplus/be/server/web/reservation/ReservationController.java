package kr.hhplus.be.server.web.reservation;

import kr.hhplus.be.server.application.port.in.ReservationUseCase;
import kr.hhplus.be.server.web.reservation.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
@Validated
public class ReservationController {

    private static final String HEADER_QUEUE_TOKEN = "X-Queue-Token";
    private static final String HEADER_IDEMPOTENCY_KEY = "Idempotency-Key";

    private final ReservationUseCase reservationUseCase;

    // 좌석 임시 배정 API
    @PostMapping("/temporary-assign")
    public ResponseEntity<TemporaryAssignResponse> temporaryAssign(
            @RequestHeader(HEADER_QUEUE_TOKEN) String queueToken,
            @RequestBody @Validated TemporaryAssignRequest request) {

        var command = new ReservationUseCase.TemporaryAssignCommand(
                queueToken,
                request.concertScheduleId(),
                request.seatNumber()
        );

        var result = reservationUseCase.temporaryAssign(command);

        var response = new TemporaryAssignResponse(
                result.reservationId(),
                result.price(),
                result.expirationTime()
        );

        return ResponseEntity.ok(response);
    }

    // 예약 확정 및 결제 API
    @PostMapping("/confirm")
    public ResponseEntity<ConfirmReservationResponse> confirmReservation(
            @RequestHeader(HEADER_QUEUE_TOKEN) String queueToken,
            @RequestHeader(HEADER_IDEMPOTENCY_KEY) String idempotencyKey,
            @RequestBody @Validated ConfirmReservationRequest request) {

        var command = new ReservationUseCase.ConfirmReservationCommand(
                queueToken,
                request.reservationId(),
                idempotencyKey
        );

        var result = reservationUseCase.confirmReservation(command);

        var response = new ConfirmReservationResponse(
                result.reservationId(),
                result.remainingBalance(),
                result.confirmedAt()
        );

        return ResponseEntity.status(201).body(response);
    }

    // 예약 조회 API
    @GetMapping("/{reservationId}")
    public ResponseEntity<ReservationInfoResponse> getReservation(
            @PathVariable String reservationId,
            @RequestParam String userId) {

        var query = new ReservationUseCase.ReservationQuery(userId, reservationId);
        var result = reservationUseCase.getReservation(query);

        var response = new ReservationInfoResponse(
                result.reservationId(),
                result.userId(),
                result.concertScheduleId(),
                result.seatNumber(),
                result.status(),
                result.price(),
                result.temporaryAssignedAt(),
                result.confirmedAt(),
                result.expirationTime()
        );

        return ResponseEntity.ok(response);
    }
}