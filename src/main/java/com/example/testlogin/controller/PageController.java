package com.example.testlogin.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Controller
public class PageController {

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/dashboard")
    public String dashboard(@AuthenticationPrincipal UserDetails userDetails,
                            HttpSession session,
                            HttpServletRequest request,
                            Model model) {

        System.out.println(session.getAttribute("SPRING_SECURITY_CONTEXT"));

        // 首次進入時記錄登錄時間
        if (session.getAttribute("loginTime") == null) {
            session.setAttribute("loginTime",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        }

        // 每次刷新累加訪問次數
        Integer count = (Integer) session.getAttribute("visitCount");
        count = (count == null) ? 1 : count + 1;
        session.setAttribute("visitCount", count);

        // Session 基本信息
        model.addAttribute("username", userDetails.getUsername());
        model.addAttribute("sessionId", session.getId());
        model.addAttribute("loginTime", session.getAttribute("loginTime"));
        model.addAttribute("visitCount", count);
        model.addAttribute("maxInactiveInterval", session.getMaxInactiveInterval()); // 單位：秒

        // Cookie 列表
        Cookie[] cookies = request.getCookies();
        List<Map<String, String>> cookieList = new ArrayList<>();
        if (cookies != null) {
            for (Cookie c : cookies) {
                cookieList.add(Map.of(
                    "name",    c.getName(),
                    "value",   c.getValue(),
                    "maxAge",  c.getMaxAge() == -1 ? "瀏覽器關閉即失效（Session Cookie）"
                                                   : c.getMaxAge() + " 秒",
                    "path",    c.getPath() != null ? c.getPath() : "/",
                    "httpOnly",String.valueOf(c.isHttpOnly()),
                    "secure",  String.valueOf(c.getSecure())
                ));
            }
        }
        model.addAttribute("cookies", cookieList);

        return "dashboard";
    }

    @GetMapping("/users")
    public String usersPage() {
        return "users";
    }
}
