package kr.hhplus.be.server.reservation.domain.exception;

public class SeatAlreadyConfirmed extends RuntimeException { public SeatAlreadyConfirmed(){ super("seat already confirmed"); } }
