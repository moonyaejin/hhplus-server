package kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.adapter;

import jakarta.persistence.OptimisticLockException;
import kr.hhplus.be.server.domain.reservation.*;
import kr.hhplus.be.server.domain.common.UserId;
import kr.hhplus.be.server.domain.common.Money;
import kr.hhplus.be.server.domain.concert.ConcertScheduleId;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.entity.ReservationJpaEntity;
import kr.hhplus.be.server.infrastructure.persistence.reservation.jpa.repository.ReservationJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ReservationJpaAdapter implements ReservationRepository {

    private final ReservationJpaRepository jpaRepository;

    @Override
    public Reservation save(Reservation reservation) {
        ReservationJpaEntity entity;

        // ID로 기존 엔티티 존재 여부 확인
        Optional<ReservationJpaEntity> existingEntity =
                jpaRepository.findById(reservation.getId().value());

        if (existingEntity.isEmpty()) {
            // 신규 생성
            entity = new ReservationJpaEntity(
                    reservation.getId().value(),
                    reservation.getUserId().asString(),
                    reservation.getSeatIdentifier().scheduleId().value(),
                    reservation.getSeatIdentifier().seatNumber().value(),
                    reservation.getPrice().amount(),
                    reservation.getStatus(),
                    reservation.getTemporaryAssignedAt(),
                    reservation.getConfirmedAt(),
                    null  // version null로 설정
            );
        } else {
            // 기존 엔티티 업데이트
            entity = existingEntity.get();

            // version 체크 (낙관적 락)
            if (!entity.getVersion().equals(reservation.getVersion())) {
                throw new OptimisticLockException(
                        "예약이 다른 트랜잭션에 의해 수정되었습니다"
                );
            }

            // 변경 가능한 필드만 업데이트
            entity.setStatus(reservation.getStatus());
            if (reservation.getConfirmedAt() != null) {
                entity.setConfirmedAt(reservation.getConfirmedAt());
            }
        }

        ReservationJpaEntity savedEntity = jpaRepository.saveAndFlush(entity);
        return toDomain(savedEntity);
    }

    @Override
    public Optional<Reservation> findById(ReservationId id) {
        return jpaRepository.findById(id.value())
                .map(this::toDomain);
    }

    @Override
    public Optional<Reservation> findByUserIdAndSeatIdentifier(UserId userId, SeatIdentifier seatIdentifier) {
        return jpaRepository.findByUserIdAndConcertScheduleIdAndSeatNumber(
                userId.asString(),
                seatIdentifier.scheduleId().value(),
                seatIdentifier.seatNumber().value()
        ).map(this::toDomain);
    }

    @Override
    public boolean existsBySeatIdentifierAndStatus(SeatIdentifier seatIdentifier, ReservationStatus status) {
        return jpaRepository.existsByConcertScheduleIdAndSeatNumberAndStatus(
                seatIdentifier.scheduleId().value(),
                seatIdentifier.seatNumber().value(),
                status
        );
    }

    @Override
    public List<Reservation> findBySeatIdentifierAndStatus(SeatIdentifier seatIdentifier, ReservationStatus status) {
        return jpaRepository.findByConcertScheduleIdAndSeatNumberAndStatus(
                        seatIdentifier.scheduleId().value(),
                        seatIdentifier.seatNumber().value(),
                        status)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public void delete(Reservation reservation) {
        jpaRepository.deleteById(reservation.getId().value());
    }

    @Override
    public List<Reservation> findByUserId(UserId userId) {
        return jpaRepository.findByUserId(userId.asString())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Reservation> findByUserIdAndStatus(UserId userId, ReservationStatus status) {
        return jpaRepository.findByUserIdAndStatus(userId.asString(), status)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Reservation> findExpiredTemporaryReservations(LocalDateTime expiredBefore) {
        return jpaRepository.findExpiredTemporaryReservations(
                        ReservationStatus.TEMPORARY_ASSIGNED,
                        expiredBefore)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Reservation> findByScheduleId(Long scheduleId) {
        return jpaRepository.findByConcertScheduleId(scheduleId)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<Reservation> findByScheduleIdAndStatus(Long scheduleId, ReservationStatus status) {
        return jpaRepository.findByConcertScheduleIdAndStatus(scheduleId, status)
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public long countByStatus(ReservationStatus status) {
        return jpaRepository.countByStatus(status);
    }

    @Override
    public long countByScheduleIdAndStatus(Long scheduleId, ReservationStatus status) {
        return jpaRepository.countByConcertScheduleIdAndStatus(scheduleId, status);
    }

    // === Private Helper Methods ===

    private ReservationJpaEntity toEntity(Reservation reservation) {
        return new ReservationJpaEntity(
                reservation.getId().value(),
                reservation.getUserId().asString(),
                reservation.getSeatIdentifier().scheduleId().value(),
                reservation.getSeatIdentifier().seatNumber().value(),
                reservation.getPrice().amount(),
                reservation.getStatus(),
                reservation.getTemporaryAssignedAt(),
                reservation.getConfirmedAt(),
                reservation.getVersion()
        );
    }

    private Reservation toDomain(ReservationJpaEntity entity) {
        return Reservation.restore(
                new ReservationId(entity.getId()),
                UserId.ofString(entity.getUserId()),
                new SeatIdentifier(
                        new ConcertScheduleId(entity.getConcertScheduleId()),
                        new SeatNumber(entity.getSeatNumber())
                ),
                new Money(entity.getPrice()),
                entity.getStatus(),
                entity.getTemporaryAssignedAt(),
                entity.getConfirmedAt(),
                entity.getVersion()
        );
    }
}