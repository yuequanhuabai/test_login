package com.example.testlogin.init;

import com.example.testlogin.entity.SysUser;
import com.example.testlogin.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DataInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        insertIfAbsent("admin", "123456");
        insertIfAbsent("user1", "123456");
        insertIfAbsent("user2", "888888");
    }

    private void insertIfAbsent(String username, String rawPassword) {
        if (userRepository.findByUsername(username).isEmpty()) {
            SysUser u = new SysUser();
            u.setUsername(username);
            u.setPassword(passwordEncoder.encode(rawPassword));
            u.setEnabled(true);
            userRepository.save(u);
            System.out.println("[DataInitializer] 創建用戶: " + username);
        }
    }
}
