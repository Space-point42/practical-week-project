package cn.edu.njupt.kms.config;

import cn.edu.njupt.kms.common.ApiResponse;
import cn.edu.njupt.kms.common.ErrorCode;
import cn.edu.njupt.kms.security.JwtAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Spring Security 配置。
 *
 * <p>无状态 JWT 认证；公开读取类接口匿名可访问，写操作按角色授权（另见各 Controller 的 {@code @PreAuthorize}）。</p>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.disable())
                .authorizeHttpRequests(auth -> auth
                        // 预检请求
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 认证入口
                        .requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
                        // 接口文档与静态资源
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**",
                                "/doc.html", "/webjars/**", "/favicon.ico", "/error").permitAll()
                        // 上传文件的匿名读取（图片/视频封面等直接以 <img src> 引用）
                        .requestMatchers(HttpMethod.GET, "/api/files/preview/**", "/files/**").permitAll()
                        // 检索与知识浏览对匿名用户开放（知识库的“可发现性”要求）
                        // 检索同时开放 POST：复杂筛选条件用请求体传递更合适，
                        // 且检索本身是只读操作，不涉及数据变更。
                        .requestMatchers("/api/search", "/api/search/advanced").permitAll()
                        .requestMatchers("/api/search/suggest", "/api/search/hot-keywords").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/search/**", "/api/knowledge/**",
                                "/api/ontology/**", "/api/categories/**", "/api/tags/**",
                                "/api/stats/**", "/api/versions/policies",
                                "/api/items/*/versions/**", "/api/versions/recent").permitAll()
                        // RAG 问答需登录（会产生会话与日志）
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> writeError(res, 401, ErrorCode.UNAUTHORIZED, "未登录或登录已过期"))
                        .accessDeniedHandler((req, res, ex) -> writeError(res, 403, ErrorCode.FORBIDDEN, "无权限访问该资源")))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    private void writeError(jakarta.servlet.http.HttpServletResponse res, int httpStatus, int code, String msg)
            throws java.io.IOException {
        res.setStatus(httpStatus);
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(code, msg)));
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        // 开发阶段放开常见前端端口；生产部署时应收紧为具体域名
        cfg.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Content-Disposition", "X-Total-Count"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
