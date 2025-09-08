package kr.hhplus.be.server.web.reservation;

import kr.hhplus.be.server.application.port.in.ConfirmPaymentUseCase;
import kr.hhplus.be.server.application.port.in.HoldSeatUseCase;
import kr.hhplus.be.server.application.dto.reservation.ConfirmRequestDto;
import kr.hhplus.be.server.application.dto.reservation.ConfirmResponseDto;
import kr.hhplus.be.server.application.dto.reservation.HoldRequestDto;
import kr.hhplus.be.server.application.dto.reservation.HoldResponseDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/reservations")
@RequiredArgsConstructor
@Validated
public class ReservationController {

    private static final String HDR_QUEUE_TOKEN = "X-Queue-Token";
    private static final String HDR_IDEMPOTENCY = "Idempotency-Key";

    private final HoldSeatUseCase holdUseCase;
    private final ConfirmPaymentUseCase confirmPaymentUseCase;

    @PostMapping("/hold")
    public ResponseEntity<HoldResponseDto> hold(
            @RequestHeader(HDR_QUEUE_TOKEN) String token,
            @RequestBody @Validated HoldRequestDto req
    ) {
        var result = holdUseCase.hold(new HoldSeatUseCase.Command(
                token, req.date(), req.seatNo()
        ));
        return ResponseEntity.ok(new HoldResponseDto(result.price(), result.holdExpiresAt()));
    }

    @PostMapping("/confirm")
    public ResponseEntity<ConfirmResponseDto> confirm(
            @RequestHeader(HDR_QUEUE_TOKEN) String token,
            @RequestHeader(HDR_IDEMPOTENCY) String idempotencyKey,
            @RequestBody @Validated ConfirmRequestDto req
    ) {
        var result = confirmPaymentUseCase.confirm(new ConfirmPaymentUseCase.Command(
                token, req.date(), req.seatNo(), idempotencyKey
        ));
        // 결제 확정은 201 Created를 쓰는 팀도 많음. (원하면 .created(URI) 로 변경)
        return ResponseEntity.status(201).body(
                new ConfirmResponseDto(result.reservationId(), result.balance(), result.paidAt())
        );
    }
}
