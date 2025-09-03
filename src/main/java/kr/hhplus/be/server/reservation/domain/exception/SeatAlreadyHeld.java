package kr.hhplus.be.server.reservation.domain.exception;

public class SeatAlreadyHeld extends RuntimeException { public SeatAlreadyHeld(){ super("seat already held"); } }

