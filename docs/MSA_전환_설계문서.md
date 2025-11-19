# MSA 전환 설계

## 1. 배포 단위 설계

### 1.1 서비스 분리

**6개 독립 서비스로 분리**

| 서비스 | 책임 | 저장소 | 분리 이유 |
|--------|------|--------|----------|
| Queue | 대기열 관리 | Redis, MySQL | 트래픽 집중, 독립 확장 필수 |
| Concert | 콘서트/좌석 관리 | MySQL, Redis | 읽기 트래픽 많음, 캐싱 최적화 |
| Reservation | 예약 처리 | MySQL | 핵심 비즈니스, Saga 조율 |
| Payment | 결제/지갑 | MySQL | 금전 관련, 독립 관리 필요 |
| User | 사용자/인증 | MySQL | 독립성 높음 |
| Ranking | 실시간 랭킹 | Redis, MySQL | 이벤트 기반, 느슨한 결합 |

### 1.2 분리 근거

**Bounded Context**
```
domain.queue       → Queue Service
domain.concert     → Concert Service
domain.reservation → Reservation Service
domain.payment     → Payment Service
```
각 도메인은 이미 독립적인 용어와 규칙을 가지고 있어 Bounded Context로 적합합니다.

**확장성 요구**

현재 병목:
```java
private static final int MAX_ACTIVE_USERS = 100;

// 동시성 테스트 결과
150명 요청 → 100명 활성(67%), 50명 대기(33%)
실제 예상: 10,000명 → 100명 활성(1%), 9,900명 대기(99%)
```

Queue Service를 독립 분리하여 Pod 10개로 확장 시 1,000명 동시 처리 가능

---

## 2. 트랜잭션 처리의 한계

### 2.1 현재 모놀리식

**하나의 트랜잭션으로 4개 도메인 처리**
```java
// ReservationService.confirmReservation()
distributedLock.executeWithLock(
    lockKey, 10L, 3, 100L,
    () -> transactionTemplate.execute(status -> {
        
        // 1. Payment: 결제 (DB)
        paymentUseCase.pay(new PaymentCommand(
            userId.asString(),
            reservation.getPrice().amount(),
            command.idempotencyKey()
        ));
        
        // 2. Reservation: 확정 (DB)
        reservation.confirm(LocalDateTime.now());
        reservationRepository.save(reservation);
        
        // 3. Concert: 좌석 해제 (Redis)
        seatHoldPort.release(reservation.getSeatIdentifier());
        
        // 4. Queue: 토큰 만료 (Redis)
        queuePort.expire(command.queueToken());
        // Redis 내부 동작:
        // - active counter 감소
        // - ACTIVE_SET에서 제거
        // - WAITING_QUEUE에서 제거
        // - token 정보 삭제
        
        // 5. Event: 랭킹 업데이트 (비동기)
        eventPublisher.publishEvent(
            ReservationConfirmedEvent.of(...)
        );
        
        return result;
    })
);
```

**특징**
- 분산락 → DB 트랜잭션 순서로 실행
- Payment, Reservation은 MySQL (ACID 보장)
- Concert, Queue는 Redis (원자적 연산)
- 4개 도메인이 하나의 흐름에서 처리

### 2.2 MSA 전환 후

**독립된 트랜잭션**
```
Payment Service    (독립 DB) → Transaction A
   ↓ HTTP
Reservation Service (독립 DB) → Transaction B
   ↓ HTTP
Concert Service    (독립 DB) → Transaction C
   ↓ HTTP
Queue Service      (독립 DB) → Transaction D
```

전체를 하나의 트랜잭션으로 묶을 수 없음.

### 2.3 ACID 한계

**Atomicity**
```
Payment 성공 → Reservation 실패
결과: 돈은 빠져나갔는데 예약 안 됨
```
부분 실패 시 자동 롤백 불가능.

**Consistency**
```
예약 확정 완료 → Ranking 업데이트 지연 (1~2초)
결과: 일시적으로 랭킹 부정확
```
모든 서비스에서 Strong Consistency 보장 불가능.

**Isolation**
```
서비스 A: 데이터 조회
서비스 B: 동시에 데이터 변경
서비스 A: 변경된 데이터로 저장
결과: Lost Update
```
독립 트랜잭션으로 전체 격리 수준 보장 불가능.

**Durability**
```
DB 저장 성공 → 이벤트 발행 실패
결과: 예약은 저장됐는데 랭킹 미반영
```
DB 커밋과 메시지 발행을 원자적으로 처리 불가능.

### 2.4 주요 시나리오

**시나리오 1: 부분 실패**
```
Payment → Reservation → Concert → Queue
문제: 토큰이 살아있어 중복 예약 가능
```

**시나리오 2: 타임아웃 애매모호**
```
Reservation → Payment 호출 (3초 타임아웃)
케이스 A: Payment 성공했지만 응답만 늦음
케이스 B: Payment 실제 실패

문제: 재시도하면 중복 결제, 안 하면 사용자 불만
```

**시나리오 3: 보상 실패**
```
Payment → Reservation → Payment 환불 시도
문제: 돈 빠져나갔는데 환불도 안 됨
```

**시나리오 4: 이벤트 순서 역전**
```
이벤트 1: 예약 확정 (10:00:00.100)
이벤트 2: 예약 취소 (10:00:00.200)
Ranking 수신: 2 먼저 → 1 나중

문제: 취소했는데 랭킹 올라감
```

---

## 3. 해결 방안

### 3.1 Orchestration

**선택 이유**
- 4개 서비스의 순차적 협력 필요
- 명확한 흐름 파악
- 보상 트랜잭션 관리 용이

**실행 흐름**
```
Reservation Service (Orchestrator)
    ↓
1. Payment 호출 → 결제
    ↓ 성공
2. Reservation 확정 → 저장
    ↓ 성공
3. Concert 호출 → 좌석 해제
    ↓ 성공
4. Queue 호출 → 토큰 만료
    ↓ 성공
5. Event 발행 → 랭킹 (비동기)
```

**보상 전략**

| 실패 지점 | 보상 작업 |
|---------|----------|
| Step 1 | 보상 불필요 |
| Step 2 | Payment 환불 |
| Step 3 | Reservation 취소 + Payment 환불 |
| Step 4 | Concert 재점유 + Reservation 취소 + Payment 환불 |

보상 실패 시: Dead Letter Queue + 관리자 알림 + 수동 개입

### 3.2 멱등성 보장

**문제**
```
사용자 버튼 2번 클릭 / 네트워크 재시도 → 중복 결제
```

**해결**

1. 클라이언트: 요청마다 고유 Idempotency Key 생성
2. 서버: Redis에 키 + 결과 저장 (24시간 TTL)
3. 동일 키 재요청 시 캐시 반환

각 서비스 적용:
- Reservation: API 레벨 검증
- Payment: Saga ID를 키로 사용, DB unique 제약
- Concert/Queue: 요청 키로 중복 방지

### 3.3 Outbox Pattern

**문제**
```
DB 저장 성공 → 메시지 브로커 장애 → 이벤트 미발행
```

**해결**

**동작 방식**
1. Reservation + Outbox Event를 같은 트랜잭션에 저장
2. 스케줄러(1초마다)가 미발행 이벤트 조회
3. Kafka로 발행 시도
4. 성공 시 processed=true, 실패 시 재시도

**Outbox 테이블**
```
outbox_events
- id, aggregate_type, aggregate_id
- event_type, payload, processed
- created_at, processed_at
```

**장점**
- At-Least-Once 보장
- DB-메시지 원자성 확보
- 장애 시에도 유실 없음

### 3.4 Eventual Consistency

**일관성 구분**

| 도메인 | 일관성 | 이유 |
|--------|--------|------|
| Payment | Strong | 결제 금액 즉시 정확 |
| Reservation | Strong | 예약 상태 즉시 정확 |
| Concert | Strong | 좌석 점유 즉시 정확 |
| Queue | Strong | 토큰 활성화 즉시 정확 |
| Ranking | Eventual | 1~2초 지연 허용 |

**이벤트 순서 문제**

해결:
- 이벤트에 version/timestamp 포함
- Ranking Service에서 늦게 도착한 이벤트 무시
- Redis에 마지막 처리 버전 저장

---

## 4. 정리

### 4.1 트레이드오프

| 항목 | 모놀리식 | MSA |
|------|---------|-----|
| 트랜잭션 | ACID | Eventual |
| 배포 | 전체 | 독립 |
| 확장 | 전체 | 서비스별 |
| 복잡도 | 낮음 | 높음 |

### 4.2 핵심 해결 방안

```
분산 트랜잭션 → Saga Orchestration
중복 요청    → Idempotency Key
이벤트 유실   → Outbox Pattern
일관성 완화   → Eventual Consistency
```

### 4.3 남은 과제

- Saga 보상 실패 시 수동 개입 프로세스
- 주기적 정합성 검증 배치
- 분산 추적 및 모니터링
- Dead Letter Queue 관리