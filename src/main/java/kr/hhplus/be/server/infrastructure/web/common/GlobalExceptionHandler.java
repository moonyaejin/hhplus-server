package kr.hhplus.be.server.infrastructure.web.common;

import kr.hhplus.be.server.domain.reservation.model.exception.*;
import kr.hhplus.be.server.domain.reservation.model.exception.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    record ErrorResponse(String code, String message) {}

    @ExceptionHandler(ForbiddenQueueAccess.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ErrorResponse handleForbiddenQueueAccess(ForbiddenQueueAccess e) { return new ErrorResponse("QUEUE_FORBIDDEN", e.getMessage()); }

    @ExceptionHandler({SeatAlreadyHeld.class, SeatAlreadyConfirmed.class, HoldNotFoundOrExpired.class})
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse handleForbiddenQueueAccess(RuntimeException e) {return new ErrorResponse("RESERVATION_CONFLICT", e.getMessage()); }

    @ExceptionHandler(InsufficientBalance.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse handleForbiddenQueueAccess(InsufficientBalance e){ return new ErrorResponse("INSUFFICIENT_BALANCE", e.getMessage()); }
}
