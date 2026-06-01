package com.demo.demo_app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LogTestController {

    private static final Logger log = LoggerFactory.getLogger(LogTestController.class);

    @GetMapping("/log/info")
    public String logInfo() {
        log.info("Ceci est un log INFO de test");
        return "INFO log envoyé";
    }

    @GetMapping("/log/warn")
    public String logWarn() {
        log.warn("Ceci est un log WARN - quelque chose d'inhabituel");
        return "WARN log envoyé";
    }

    @GetMapping("/log/error")
    public String logError() {
        try {
            String s = null;
            s.length();
        } catch (NullPointerException e) {
            log.error("NullPointerException simulée pour le test ELK", e);
        }
        return "ERROR log envoyé avec stack trace";
    }
}