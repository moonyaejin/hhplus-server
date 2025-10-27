package kr.hhplus.be.server.infrastructure.redis;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class RedisConnectionTest {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Test
    void Redis_연결_테스트() {
        // given
        String key = "test:connection";
        String value = "hello redis";

        // when
        redisTemplate.opsForValue().set(key, value);
        String result = redisTemplate.opsForValue().get(key);

        // then
        assertThat(result).isEqualTo(value);

        // cleanup
        redisTemplate.delete(key);
    }

    @Test
    void Redis_SETNX_테스트() {
        // given
        String key = "test:setnx";
        String value1 = "first";
        String value2 = "second";

        // cleanup first (혹시 이전 테스트 데이터가 남아있을 수 있으니)
        redisTemplate.delete(key);

        // when: 첫 번째 시도
        Boolean success1 = redisTemplate.opsForValue().setIfAbsent(key, value1);

        // then: 성공
        assertThat(success1).isTrue();

        // when: 두 번째 시도 (같은 키)
        Boolean success2 = redisTemplate.opsForValue().setIfAbsent(key, value2);

        // then: 실패
        assertThat(success2).isFalse();

        // when: 값 확인
        String result = redisTemplate.opsForValue().get(key);

        // then: 첫 번째 값이 유지됨
        assertThat(result).isEqualTo(value1);

        // cleanup
        redisTemplate.delete(key);
    }

    @Test
    void Redis_SETNX_with_TTL_테스트() {
        // given
        String key = "test:setnx:ttl";
        String value = "with-ttl";

        // cleanup first
        redisTemplate.delete(key);

        // when: TTL 5초로 설정
        Boolean success = redisTemplate.opsForValue()
                .setIfAbsent(key, value, Duration.ofSeconds(5));

        // then: 성공
        assertThat(success).isTrue();

        // when: 값 확인
        String result = redisTemplate.opsForValue().get(key);

        // then: 값이 있음
        assertThat(result).isEqualTo(value);

        // when: TTL 확인 (초 단위)
        Long ttl = redisTemplate.getExpire(key);

        // then: TTL이 5초 이하 (약간의 시간이 지났으므로)
        assertThat(ttl).isLessThanOrEqualTo(5L);
        assertThat(ttl).isGreaterThan(0L);

        // cleanup
        redisTemplate.delete(key);
    }
}