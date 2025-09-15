package kr.hhplus.be.server.web.common;

import kr.hhplus.be.server.domain.common.exception.ConcertScheduleNotFoundException;
import kr.hhplus.be.server.domain.payment.InsufficientBalanceException;
import kr.hhplus.be.server.domain.payment.WalletNotFoundException;
import kr.hhplus.be.server.domain.queue.QueueTokenNotActiveException;
import kr.hhplus.be.server.domain.reservation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    record ErrorResponse(String code, String message) {}

    // ========== 대기열 관련 예외 ==========
    @ExceptionHandler(QueueTokenExpiredException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ErrorResponse handleQueueTokenExpired(QueueTokenExpiredException e) {
        return new ErrorResponse("QUEUE_TOKEN_EXPIRED", e.getMessage());
    }

    @ExceptionHandler(QueueTokenNotActiveException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ErrorResponse handleQueueTokenNotActive(QueueTokenNotActiveException e) {
        return new ErrorResponse("QUEUE_TOKEN_NOT_ACTIVE", e.getMessage());
    }

    // ========== 예약 관련 예외 ==========
    @ExceptionHandler(SeatAlreadyAssignedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse handleSeatAlreadyAssigned(SeatAlreadyAssignedException e) {
        return new ErrorResponse("SEAT_ALREADY_ASSIGNED", e.getMessage());
    }

    @ExceptionHandler(SeatAlreadyConfirmedException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse handleSeatAlreadyConfirmed(SeatAlreadyConfirmedException e) {
        return new ErrorResponse("SEAT_ALREADY_CONFIRMED", e.getMessage());
    }

    @ExceptionHandler(DuplicateReservationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    ErrorResponse handleDuplicateReservation(DuplicateReservationException e) {
        return new ErrorResponse("DUPLICATE_RESERVATION", e.getMessage());
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ErrorResponse handleReservationNotFound(ReservationNotFoundException e) {
        return new ErrorResponse("RESERVATION_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ReservationExpiredException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse handleReservationExpired(ReservationExpiredException e) {
        return new ErrorResponse("RESERVATION_EXPIRED", e.getMessage());
    }

    @ExceptionHandler(InvalidReservationStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse handleInvalidReservationState(InvalidReservationStateException e) {
        return new ErrorResponse("INVALID_RESERVATION_STATE", e.getMessage());
    }

    @ExceptionHandler(UnauthorizedReservationAccessException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    ErrorResponse handleUnauthorizedAccess(UnauthorizedReservationAccessException e) {
        return new ErrorResponse("UNAUTHORIZED_RESERVATION_ACCESS", e.getMessage());
    }

    // ========== 콘서트 관련 예외 ==========
    @ExceptionHandler(ConcertScheduleNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ErrorResponse handleConcertScheduleNotFound(ConcertScheduleNotFoundException e) {
        return new ErrorResponse("CONCERT_SCHEDULE_NOT_FOUND", e.getMessage());
    }

    // ========== 결제 관련 예외 ==========
    @ExceptionHandler(InsufficientBalanceException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse handleInsufficientBalance(InsufficientBalanceException e) {
        return new ErrorResponse("INSUFFICIENT_BALANCE", e.getMessage());
    }

    @ExceptionHandler(WalletNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    ErrorResponse handleWalletNotFound(WalletNotFoundException e) {
        return new ErrorResponse("WALLET_NOT_FOUND", e.getMessage());
    }

    // ========== 일반 예외 ==========
    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse handleIllegalState(IllegalStateException e) {
        return new ErrorResponse("INVALID_STATE", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    ErrorResponse handleIllegalArgument(IllegalArgumentException e) {
        return new ErrorResponse("INVALID_ARGUMENT", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    ErrorResponse handleGenericException(Exception e) {
        return new ErrorResponse("INTERNAL_ERROR", "서버 내부 오류가 발생했습니다");
    }
}