# 🎨 ERD – 콘서트 좌석 예매 서비스

---

## 📌 ERD 다이어그램
![ERD](./docs/images/ERD.png)

---

## 📌 주요 엔티티 설명

### 👤 User & Wallet
- **users**: 회원 기본 정보 (이메일, 비밀번호, 이름, 전화번호, 상태)
- **wallets**: 회원 1:1 포인트 지갑 (현재 잔액)
- **wallet_histories**: 포인트 충전/사용/환불 변경 이력

### 🎵 Concert / Schedule / Seat
- **concerts**: 공연 기본 정보 (제목)
- **concert_schedules**: 공연 일정 및 장소
- **concert_seats**: 일정별 좌석 정보 (번호, 등급, 가격, 상태)

### 🎫 Reservation / Payment
- **reservations**: 좌석 예약 내역 (임시 배정 → 확정 → 취소 → 만료)
- **payments**: 예약 건의 결제 (포인트/카드/계좌)
- **payment_histories**: 결제 시도/성공/실패/환불 이력

### ⏳ QueueToken
- **queue_tokens**: 대기열 토큰 관리 (UUID, 순번, 상태, 생성일시, 만료일시, 마지막 조회)
