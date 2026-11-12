package fr.aeria.backoffice.config;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.stereotype.Component;

/**
 * Security events and password-guessing protection (audit findings F3 and F7 of the security module).
 *
 * <ul>
 *   <li>Every login success or failure, lock and access refusal is logged on the {@code AERIA_SECURITY} logger
 *       in a stable key=value format, decoded by the Wazuh rules of the security module.</li>
 *   <li>After {@value #MAX_FAILURES} failures within {@link #WINDOW}, the account is locked for {@link #LOCK}:
 *       even the right password is refused. The login page shows the same generic message (no user enumeration).</li>
 * </ul>
 * State is in memory: a restart clears the counters, which is acceptable for a single-instance back-office.
 */
@Component
public class SecurityEvents {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);
    static final Duration LOCK = Duration.ofMinutes(15);

    private static final Logger SECURITY = LoggerFactory.getLogger("AERIA_SECURITY");

    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lockedUntil = new ConcurrentHashMap<>();
    private final Clock clock;

    public SecurityEvents() {
        this(Clock.systemUTC());
    }

    SecurityEvents(Clock clock) {
        this.clock = clock;
    }

    public boolean isLocked(String login) {
        Instant until = lockedUntil.get(key(login));
        if (until == null) {
            return false;
        }
        if (clock.instant().isAfter(until)) {
            lockedUntil.remove(key(login));
            return false;
        }
        return true;
    }

    @EventListener
    public void onFailure(AbstractAuthenticationFailureEvent event) {
        String login = String.valueOf(event.getAuthentication().getPrincipal());
        String ip = ip(event.getAuthentication().getDetails());
        String reason = event.getException().getClass().getSimpleName();
        SECURITY.warn("AERIA-SECURITY event=login_failure user={} ip={} reason={}", clean(login), ip, reason);
        if (isLocked(login)) {
            return;
        }
        Instant now = clock.instant();
        Deque<Instant> q = failures.computeIfAbsent(key(login), k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(now);
            while (!q.isEmpty() && q.peekFirst().isBefore(now.minus(WINDOW))) {
                q.removeFirst();
            }
            if (q.size() >= MAX_FAILURES) {
                lockedUntil.put(key(login), now.plus(LOCK));
                q.clear();
                SECURITY.warn("AERIA-SECURITY event=account_locked user={} ip={} minutes={}", clean(login), ip,
                        LOCK.toMinutes());
            }
        }
    }

    @EventListener
    public void onSuccess(AuthenticationSuccessEvent event) {
        String login = event.getAuthentication().getName();
        failures.remove(key(login));
        SECURITY.info("AERIA-SECURITY event=login_success user={} ip={}", clean(login),
                ip(event.getAuthentication().getDetails()));
    }

    public void accessDenied(String login, String ip, String method, String path) {
        SECURITY.warn("AERIA-SECURITY event=access_denied user={} ip={} method={} path={}", clean(login), ip, method,
                clean(path));
    }

    private static String ip(Object details) {
        return details instanceof WebAuthenticationDetails w ? w.getRemoteAddress() : "-";
    }

    private static String key(String login) {
        return login == null ? "" : login.strip().toLowerCase();
    }

    /** No spaces or control characters in log fields: a login cannot forge another log line. */
    private static String clean(String value) {
        return value == null ? "-" : value.replaceAll("[\\s\\p{Cntrl}]", "_");
    }
}
