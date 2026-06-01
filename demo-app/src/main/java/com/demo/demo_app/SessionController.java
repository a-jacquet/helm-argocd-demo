package com.demo.demo_app;

import jakarta.servlet.http.HttpSession;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
public class SessionController {

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(
            @RequestParam String username,
            HttpSession session) {
        session.setAttribute("username", username);
        return ResponseEntity.ok(Map.of(
            "sessionId", session.getId(),
            "message", "Connecte en tant que " + username
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(HttpSession session) {
        String username = (String) session.getAttribute("username");
        if (username == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Non connecte"));
        }
        return ResponseEntity.ok(Map.of(
            "username", username,
            "sessionId", session.getId()
        ));
    }
}
