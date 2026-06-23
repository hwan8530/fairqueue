package com.example.eventplatform.database;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class DatabaseConfig {

  /*
   * Redis 동작을 구현해줄 RedisTemplate 객체 반환
   */
  @Bean
  public RedisTemplate<String, Object> redisTemplate(
      RedisConnectionFactory redisConnectionFactory) {
    RedisTemplate<String, Object> template = new RedisTemplate<>();
    template.setConnectionFactory(redisConnectionFactory);

    template.setKeySerializer(new StringRedisSerializer());
    template.setValueSerializer(new StringRedisSerializer());
    template.setHashKeySerializer(new StringRedisSerializer());
    template.setHashValueSerializer(new StringRedisSerializer());
    return template;
  }

  /*
   * Redis의 비동기성 처리를 위한 ThreadPool
   */
  @Bean(name = "redisAsyncExecutor")
  public Executor redisAsyncExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5); // 기본 스레드 개수
    executor.setMaxPoolSize(10); // 트래픽이 몰렸을 때 확장될 최대 스레드 개수
    executor.setQueueCapacity(100); // 큐 최대 크기
    executor.setThreadNamePrefix("RedisAsyncExecutor-"); // 스레드 이름 접두사 (로그 확인용)
    executor.initialize();
    return executor;
  }
}
