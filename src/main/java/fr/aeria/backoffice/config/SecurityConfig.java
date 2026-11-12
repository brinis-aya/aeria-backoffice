package fr.aeria.backoffice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * Two roles (RG16):
 * <ul>
 *   <li>RESPONSABLE - qualifies, assigns, closes and cancels tickets, manages technicians and stock;</li>
 *   <li>TECHNICIEN - sees the fleet and the tickets, starts and finishes <em>their own</em> interventions.</li>
 * </ul>
 * Accounts live in the {@code utilisateur} table (BCrypt hashes). Repeated failures lock the account
 * ({@link SecurityEvents}).
 */
@Configuration
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(JdbcTemplate jdbc, SecurityEvents events) {
        return login -> jdbc.query(
                "SELECT login, mot_de_passe, role, actif, technicien_id FROM utilisateur WHERE login = ?",
                rs -> {
                    if (!rs.next()) {
                        throw new UsernameNotFoundException(login);
                    }
                    Integer tech = (Integer) rs.getObject("technicien_id");
                    return new AeriaUser(rs.getString("login"), rs.getString("mot_de_passe"),
                            rs.getBoolean("actif"), !events.isLocked(login), rs.getString("role"), tech);
                }, login);
    }

    @Bean
    AccessDeniedHandler accessDeniedHandler(SecurityEvents events) {
        AccessDeniedHandlerImpl delegate = new AccessDeniedHandlerImpl();
        return (request, response, ex) -> {
            events.accessDenied(request.getRemoteUser(), request.getRemoteAddr(), request.getMethod(),
                    request.getRequestURI());
            delegate.handle(request, response, ex);
        };
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AccessDeniedHandler accessDeniedHandler) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/css/**", "/favicon.svg", "/actuator/health").permitAll()
                .requestMatchers(HttpMethod.POST, "/tickets/nouveau", "/tickets/*/planifier", "/tickets/*/cloturer",
                        "/tickets/*/rouvrir", "/tickets/*/annuler", "/supervision/**").hasRole("RESPONSABLE")
                .requestMatchers("/tickets/nouveau").hasRole("RESPONSABLE")
                .anyRequest().authenticated())
            .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", false).permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/login?deconnecte"))
            .httpBasic(Customizer.withDefaults())  // read-only JSON API for the dashboard (module 6)
            .exceptionHandling(ex -> ex
                .accessDeniedHandler(accessDeniedHandler)
                // the JSON API answers 401, every page redirects to the login form (whatever the Accept header)
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        request -> request.getRequestURI().startsWith("/api/"))
                .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"), request -> true))
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; form-action 'self'"))
                .frameOptions(f -> f.deny())
                .referrerPolicy(r -> r.policy(ReferrerPolicy.SAME_ORIGIN))
                .permissionsPolicyHeader(p -> p.policy("geolocation=(), camera=(), microphone=(), payment=()")));
        return http.build();
    }
}
