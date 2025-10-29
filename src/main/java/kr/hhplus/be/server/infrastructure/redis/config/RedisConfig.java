package kr.hhplus.be.server.infrastructure.redis.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

@Configuration
@EnableCaching
public class RedisConfig {

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // 기본 캐시 설정 - JSON Serializer 사용
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new StringRedisSerializer()
                        )
                )
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(
                                new GenericJackson2JsonRedisSerializer()  // JSON으로 변경
                        )
                );

        // 콘서트 목록 캐시 설정: 1일 TTL
        RedisCacheConfiguration concertsConfig = defaultConfig.entryTtl(Duration.ofDays(1));

        // 콘서트 상세 캐시 설정: 1일 TTL
        RedisCacheConfiguration concertDetailConfig = defaultConfig.entryTtl(Duration.ofDays(1));

        // 스케줄 캐시 설정: 1분 TTL
        RedisCacheConfiguration scheduleConfig = defaultConfig.entryTtl(Duration.ofMinutes(1));

        // 랭킹 캐시 설정: 10초 TTL (실시간성 유지)
        RedisCacheConfiguration rankingConfig = defaultConfig.entryTtl(Duration.ofSeconds(10));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig.entryTtl(Duration.ofMinutes(10)))
                .withCacheConfiguration("concerts", concertsConfig)
                .withCacheConfiguration("concertDetail", concertDetailConfig)
                .withCacheConfiguration("schedule", scheduleConfig)
                .withCacheConfiguration("concertRankings", rankingConfig)
                .build();
    }
}