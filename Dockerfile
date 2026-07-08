# 1단계: 빌드 스테이지 (Gradle 빌드 수행)
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# Gradle 래퍼 및 설정 파일들을 먼저 복사 (의존성 캐싱을 위함)
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .

# 라이브러리 다운로드 및 캐싱 (소스 코드가 바뀌어도 라이브러리는 다시 받지 않음)
RUN ./gradlew dependencies --no-daemon

# 소스 코드 복사 후 빌드 (테스트는 제외하고 jar 생성)
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# 2단계: 실행 스테이지 (최종 경량화 이미지 생성)
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 빌드 스테이지에서 생성된 jar 파일만 쏙 빼오기
COPY --from=builder /app/build/libs/*-SNAPSHOT.jar app.jar

# 컨테이너가 사용할 포트 명시 (스프링 기본포트 8080)
EXPOSE 8080

# 애플리케이션 실행 명령
ENTRYPOINT ["java", "-jar", "app.jar"]