package com.example.eventplatform.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestSupport {

  @Autowired
  protected RestClient.Builder restClientBuilder;

  @Autowired
  protected WebClient.Builder webClientBuilder;

  @LocalServerPort
  protected int port;

  protected RestClient syncClient() {
    return restClientBuilder.baseUrl("http://localhost:" + port).build();
  }

  protected WebClient asyncClient() {
    return webClientBuilder.baseUrl("http://localhost:" + port).build();
  }

  static final PostgreSQLContainer<?> POSTGRE_SQL_CONTAINER = new PostgreSQLContainer<>(
      "postgres:16-alpine")
      .withDatabaseName("testdb")
      .withUsername("test")
      .withPassword("test");

  // 1. Redis 컨테이너 생성 (GenericContainer 사용)
  static final GenericContainer<?> REDIS_CONTAINER =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379); // 컨테이너 내부 포트 지정

  // 2. Kafka 컨테이너 생성 (공식 KafkaContainer 사용, KRaft 모드 자동 지원)
  static final KafkaContainer KAFKA_CONTAINER =
      new KafkaContainer(DockerImageName.parse("apache/kafka:latest"));

  static {
    POSTGRE_SQL_CONTAINER.start(); // 컨테이너 구동
    REDIS_CONTAINER.start();
    KAFKA_CONTAINER.start();
  }

  // 동적으로 할당된 포트를 Spring Boot 설정에 주입
  @DynamicPropertySource
  static void configureProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRE_SQL_CONTAINER::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRE_SQL_CONTAINER::getUsername);
    registry.add("spring.datasource.password", POSTGRE_SQL_CONTAINER::getPassword);
    registry.add("spring.data.redis.host", REDIS_CONTAINER::getHost);
    registry.add("spring.data.redis.port", () -> REDIS_CONTAINER.getMappedPort(6379));
    registry.add("spring.kafka.bootstrap-servers", KAFKA_CONTAINER::getBootstrapServers);
  }
}
