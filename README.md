# FairQueue — 신뢰성 있는 비동기 선착순 발급 큐

FairQueue는 한정된 수량의 쿠폰/티켓을 짧은 시간에 폭주하는 대량 동시 요청 환경에서 선착순으로 정확하게 발급하기 위해 설계된 서비스입니다. 빠른 응답(요청 수락 후 비동기
처리)과 발급 정확도를 최우선으로 하며, Redis/Kafka/PostgreSQL을 조합해 신뢰성 있는 작업 큐 패턴으로 구현되어 있습니다.

## 핵심 목표

- 동시성 폭주 상황에서 과잉 발급 방지 및 선착순 보장
- 빠른 초기 응답(비동기 위임)과 안정적 백그라운드 처리
- 장애 복구, 재시도, 중복 방지, 감사 로깅

## 기술 스택

- Language: Java
- Framework: Spring Boot 4.1.0
- Database: PostgreSQL
- Cache / fast store: Redis
- Message broker: Kafka (primary), Redis key expiration 이벤트(보조 트리거)
- Build: Gradle (프로젝트는 Gradle wrapper 사용)
- Container: Docker / Docker Compose

## 아키텍처 개요

1. 클라이언트 요청 → API는 빠르게 수락(ack)하고 작업을 비동기 큐로 위임
2. Redis에서 잔여 수량을 atomic하게 체크 및 예약(예: Lua script 활용)
3. 예약 성공 시 Kafka 토픽으로 작업 발행
4. 워커(consumer)가 Kafka 메시지를 처리해 PostgreSQL에 발급 확정
5. Redis key 만료(expire) 이벤트를 수신하여 예약 해제/롤백 처리

Redis는 빠른 카운팅과 예약(임시 토큰), 만료 이벤트 감지(notify-keyspace-events)를 담당하며, Kafka는 안정적인 메시지 전달과 소비자 확장성을
제공합니다. 발급 확정은 PostgreSQL 트랜잭션과 고유 제약(UNIQUE)을 통해 중복을 방지합니다.

## 정합성 보장 전략 (권장)

- Redis에서 재고 감소는 Lua 스크립트로 atomic 수행
- Redis 감소 성공 시에만 Kafka에 메시지 발행
- DB 발급 처리 시 UNIQUE(user_id, coupon_id) 같은 제약으로 중복 방지
- Kafka consumer는 처리 완료 시에만 오프셋을 커밋(enable-auto-commit=false)
- idempotency token 사용으로 재시도 안전성 확보
- 실패 메시지는 Dead-letter topic/DLQ로 분리

## 운영·모니터링 포인트

- Kafka lag, Redis 메모리/만료 이벤트, PostgreSQL slow query 모니터링
- 재시도 정책(지수적 백오프), DLQ 관찰 및 재처리
- Redis notify-keyspace-events 설정 확인
- 데이터 백업 및 retention 정책(Changelog/Events 로그 포함)

## 설치 및 실행 (예시)

### 포함된 파일 요약

- Dockerfile
    - Gradle builder multi-stage: Gradle wrapper(gradlew)로 build (`./gradlew bootJar -x test`) 후 경량
      JRE 이미지에 jar 복사
    - EXPOSE 8080, ENTRYPOINT ["java","-jar","app.jar"]
- compose.yaml
    - services: `app`, `postgres-db`, `redis-cache`, `kafka-broker`
    - 포트 매핑: app(8080:8080), postgres(5432:5432), redis(6379:6379), kafka external(29092:29092)
    - Redis는 만료 이벤트 수신을 위해 `redis-server --notify-keyspace-events Ex`로 실행
    - Kafka는 KRaft 모드로 구성(내부 9092, 외부 29092) — application은 외부 포트(29092)를 사용하도록 설정되어 있음
    - 테스트 및 디버그를 위해 컨테이너 내부와 외부를 나눠서 포트 매핑
- src/main/resources/application.yaml
    - 데이터베이스 및 메시지 브로커 연결을 환경변수로 읽음
        - SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD
        - SPRING_DATA_REDIS_HOST
        - SPRING_KAFKA_BOOTSTRAP_SERVERS (기본: localhost:29092)
    - Kafka consumer: `enable-auto-commit: false`, `group-id: event-worker-group`, listener
      ack-mode: manual_immediate

### 로컬 실행 (Docker Compose)

1. Docker와 Docker Compose가 설치되어 있는지 확인
2. 루트 디렉토리에서 빌드 및 실행

```bash
# 이미지 빌드 및 모든 서비스 시작 (빌드 포함)
docker compose up --build

# 백그라운드로 실행
docker compose up -d --build

# 로그 보기
docker compose logs -f app
```

### 환경 변수/설정 주의사항

- application.yaml은 환경변수로 DB/Redis/Kafka 설정을 읽습니다. compose.yaml의 environment 항목이 기본값과 일치하도록 설정되어
  있습니다.
    - 예: SPRING_DATASOURCE_URL=jdbc:postgresql://postgres-db:5432/eventdb
    - 예: SPRING_DATA_REDIS_HOST=redis-cache
    - 예: SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:29092 (또는 kafka-broker:9092 내부 통신)
- Redis는 만료 이벤트 처리를 위해 `notify-keyspace-events Ex` 설정이 필요합니다(compose.yaml에서 이미 적용).
- Kafka의 경우 외부 포트(29092)를 애플리케이션에서 참조하도록 맞춰야 합니다.

## 테스트 및 검증 권장 항목 (구현 예정)

- 단위 테스트: 서비스 로직, Lua 스크립트
- 통합 테스트: Redis/Kafka/PostgreSQL 연동 테스트
- 부하 테스트: 동시성 발급 시나리오(k6, Gatling 등)로 발급 정확성 검증
