# ===========================================
# 콘서트 예약 서비스 Docker 이미지
# ===========================================

# Build stage - git이 포함된 이미지 사용
FROM gradle:8.5-jdk17-jammy AS builder

WORKDIR /app

COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY .git ./.git

RUN gradle dependencies --no-daemon || true

COPY src ./src

RUN gradle bootJar --no-daemon -x test

# Runtime stage
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]