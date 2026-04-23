package com.example.testlogin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login").permitAll()   // 登錄頁公開
                .anyRequest().authenticated()            // 其餘全部需要登錄
            )
            .formLogin(form -> form
                .loginPage("/login")                     // 自定義登錄頁地址
                .defaultSuccessUrl("/dashboard", true)   // 登錄成功跳轉
                .failureUrl("/login?error")              // 登錄失敗跳轉
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")       // 登出後跳轉
                .permitAll()
            )
            .rememberMe(remember -> remember
                .key("test-login-remember-me-key")       // 簽名密鑰，防止偽造
                .tokenValiditySeconds(7 * 24 * 60 * 60) // Cookie 有效期 7 天
                .rememberMeParameter("remember-me")      // 登錄表單中的字段名
            );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
