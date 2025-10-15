# 콘서트 예약 서비스 동시성 제어 구현

## 📌 개요
이번 과제에서는 콘서트 예약 서비스에서 발생할 수 있는 동시성 문제를 파악하고 해결했습니다. 실제 서비스 환경에서 여러 사용자가 동시에 요청할 때 발생할 수 있는 문제들을 예방하는 것이 목표였습니다.

## 🔍 문제 상황 분석

### 1. 좌석 중복 예약 문제
- 같은 좌석에 대해 동시에 예약 요청 → **중복 예약 발생**

### 2. 잔액 음수 발생 문제
- 잔액 차감 중 충돌 발생 → **음수 잔액**

### 3. 타임아웃 미처리
- 예약 후 결제 지연 → **임시 배정 해제 로직 부정확**

## 💡 해결 전략

### 1. 좌석 예약 - 트랜잭션 서비스 분리
이거 해결하느라 꽤 애를 먹었습니다. 처음엔 왜 안 되는지 몰라서 한참 헤맸는데, 알고보니 트랜잭션 프록시 문제였습니다.

```java
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class SeatHoldTransactionService {
    // 별도 서비스로 분리
}
```

그리고 DB에 UNIQUE 제약조건 걸어서 아예 중복 자체가 불가능하게 만들었습니다. `DataIntegrityViolationException` 나면 이미 누가 먼저 예약했기 때문에 false 리턴!

### 2. 잔액 차감 - 조건부 UPDATE로 해결

```java
UPDATE user_wallet 
SET balance = balance - :amount 
WHERE user_id = :userId 
  AND balance >= :amount  // 음수 방지
```

WHERE 절에 조건 하나 추가했을 뿐인데 해결이 됐습니다. 업데이트된 행이 0개면 잔액 부족으로 최대한 깔끔하게 해결하기 위해 노력했습니다.

추가로 비관적 락도 구현해놨는데, 설정으로 전환 가능하게 만들었습니다.
```java
@Value("${app.payment.use-conditional-update:false}")
private boolean useConditionalUpdate;
```

### 3. 스케줄러로 자동 정리
```java
@Scheduled(fixedDelay = 10000)  // 10초마다

public void cleanupExpiredSeatHolds() {
    // 만료된 좌석 자동 해제
}

```
10초마다 돌면서 만료된 좌석 정리해주는 스케줄러 만들었습니다. 빠른 테스트를 위해 10초로 설정했습니다.

## 🖥️ 테스트 결과

### 멀티스레드 테스트 작성

```java
CountDownLatch startLatch = new CountDownLatch(1);
// 모든 스레드가 startLatch.await()에서 대기
startLatch.countDown(); // 동시 시작
```

### 테스트 결과 정리

#### 1️⃣ 같은 좌석 100명 동시 예약
- **결과**: 1명만 성공, 99명 실패 
- 처음엔 여러 명 성공했었는데 수정 후 성공.

#### 2️⃣ 잔액 차감 테스트
- **초기 잔액**: 10,000원
- **결과**: 딱 10명만 성공, 잔액 정확히 0원 
- 음수 안 됨. 성공.

#### 3️⃣ 타임아웃 테스트
- 점유 → 5초 대기 → 만료 → 다른 사용자 점유 성공 

#### 4️⃣ 극한 테스트
- 1000명 동시 요청해도 1명만 성공 
- 500명 동시 결제도 정상 처리 

##  트러블슈팅

### 1. 트랜잭션 프록시 이슈
같은 클래스 내에서 `@Transactional` 메서드 호출하면 프록시를 안 거친다는 걸 처음 알았습니다...

그래서 `SeatHoldTransactionService` 별도로 만들어서 해결했습니다.

### 2. ExecutorService 문제
```java
// 수정 전
ExecutorService executor = Executors.newFixedThreadPool(10);

// 수정 후
Thread thread = new Thread(() -> { ... });
```
이 부분은 왜 안 되는지 아직도 정확히는 모르겠습니다.

### 3. 서로 다른 좌석 동시 예약 테스트
이 부분이 가장 힘들었던 것 같습니다. 분명 다른 좌석인데 왜 1개만 성공하지? 했는데 Thread를 직접 사용하고, 랜덤 딜레이를 추가하여 해결해봤습니다.

## 💭 느낀 점

동시성 제어가 너무 어렵게 느껴졌습니다. 특히 테스트 환경에서 재현하는 게 제일 힘들었습니다. 하지만 멀티스레드 테스트 작성하면서 정말 많이 배웠고, 뿌듯함도 느꼈습니다.
하지만 그만큼 부족한 점도 많이 느끼게 됐습니다.

앞으로는:
- 처음부터 동시성 고려해서 설계하기
- 테스트 꼼꼼히 작성하기 (특히 멀티스레드!)
- 트랜잭션 범위 잘 생각하기

실무에서도 이런 상황이 많이 발생할 텐데, 미리 경험해볼 수 있어서 좋았습니다.