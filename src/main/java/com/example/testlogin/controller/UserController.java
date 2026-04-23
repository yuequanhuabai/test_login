package com.example.testlogin.controller;

import com.example.testlogin.repository.UserRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    // 此接口受 Spring Security 保護，未登錄訪問會返回 302/401
    // 登錄後瀏覽器請求時會自動帶上 JSESSIONID Cookie，服務端識別 Session 後放行
    @GetMapping("/users")
    public List<UserVO> listUsers() {
        return userRepository.findAll().stream()
                .map(u -> new UserVO(u.getId(), u.getUsername(), u.getEnabled()))
                .toList();
    }
}
