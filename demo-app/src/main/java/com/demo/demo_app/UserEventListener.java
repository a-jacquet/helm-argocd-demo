package com.demo.demo_app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

@Component
public class UserEventListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(UserEventListener.class);

    @Override
    public void onMessage(Message message, byte[] pattern) {
        log.info(">>> Event reçu sur canal Redis : {}", message.toString());
    }
}