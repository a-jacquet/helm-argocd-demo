package com.demo.demo_app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class UserEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(UserEventPublisher.class);
    private static final String CHANNEL = "channel:users";
    private final StringRedisTemplate redis;

    public UserEventPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void publishCreated(User user) {
        String message = "USER_CREATED:" + user.getId() + ":" + user.getUsername();
        redis.convertAndSend(CHANNEL, message);
        log.info(">>> Event publié sur {} : {}", CHANNEL, message);
    }
}