package com.demo.demo_app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);
    private final UserRepository repo;

    private final UserEventPublisher publisher;

    public UserService(UserRepository repo, UserEventPublisher publisher) {
        this.repo = repo;
        this.publisher = publisher;
    }

    @Cacheable(value = "users", key = "#id")
    public User findById(Long id) {
        log.info(">>> CACHE MISS - requete PostgreSQL pour userId={}", id);
        return repo.findById(id).orElseThrow();
    }

    @CachePut(value = "users", key = "#result.id")
    public User create(User user) {
        User saved = repo.save(user);
        publisher.publishCreated(saved);
        return saved;
    }

    @CacheEvict(value = "users", key = "#id")
    public User update(Long id, User updated) {
        log.info(">>> Mise a jour userId={} - cache invalide", id);
        User user = repo.findById(id).orElseThrow();
        user.setUsername(updated.getUsername());
        user.setEmail(updated.getEmail());
        return repo.save(user);
    }
}
