## 📡 인프라 구성도

### 다이어그램
![Infra Diagram](/docs/images/infra-diagram.png)

- **Nginx (Load Balancer)**  
  → 유저 요청을 받아서 여러 서버로 나눠주는 역할을 합니다.

- **Spring Boot (Docker 컨테이너, Auto Scaling)**  
  → 실제 비즈니스 로직이 돌아가는 애플리케이션 서버입니다. 여러 개 컨테이너로 띄워서 트래픽이 많아도 대응할 수 있도록 합니다.

- **Redis (Queue & Seat Hold)**  
  → 대기열 토큰이나 좌석 임시 배정 정보를 잠깐 저장합니다.

- **MySQL**  
  → 유저, 예약, 결제 등 데이터를 저장하는 DB입니다.  

- **GitHub Actions (CI/CD)**  
  → 코드를 푸시하면 자동으로 빌드하고 서버에 배포할 수 있게 합니다.
