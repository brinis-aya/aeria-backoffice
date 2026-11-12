package fr.aeria.backoffice.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import fr.aeria.backoffice.db.Db;

/**
 * Automatic tasks of the BPMN processes:
 * <ul>
 *   <li>« Anomalie détectée » (maintenance corrective): {@code detecter_anomalies()}, every 5 minutes (RG14);</li>
 *   <li>« Chaque jour à 6 h » (étalonnage préventif): {@code planifier_etalonnages()} (RG15).</li>
 * </ul>
 * When {@code aeria.supervision.iot-url} points to module 1, sensor health (last message, counters) is
 * pulled from its REST API first.
 */
@Service
public class SupervisionService {

    private static final Logger log = LoggerFactory.getLogger(SupervisionService.class);
    private static final String ACTOR = "supervision";

    private final JdbcTemplate jdbc;
    private final Db db;
    private final String iotUrl;

    public SupervisionService(JdbcTemplate jdbc, Db db, @Value("${aeria.supervision.iot-url:}") String iotUrl) {
        this.jdbc = jdbc;
        this.db = db;
        this.iotUrl = iotUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void atStartup() {
        detecter();
        planifierEtalonnages();
    }

    @Scheduled(cron = "${aeria.supervision.anomalies-cron}")
    public int detecter() {
        if (!iotUrl.isBlank()) {
            synchroniser();
        }
        Integer n = db.asUser(ACTOR, () -> jdbc.queryForObject("SELECT detecter_anomalies()", Integer.class));
        if (n != null && n > 0) {
            log.info("supervision: {} ticket(s) opened automatically", n);
        }
        return n == null ? 0 : n;
    }

    @Scheduled(cron = "${aeria.supervision.etalonnages-cron}")
    public int planifierEtalonnages() {
        Integer n = db.asUser(ACTOR, () -> jdbc.queryForObject("SELECT planifier_etalonnages()", Integer.class));
        if (n != null && n > 0) {
            log.info("supervision: {} preventive calibration ticket(s) opened", n);
        }
        return n == null ? 0 : n;
    }

    /** Copies the health of the sensors known by module 1 into the fleet. Unknown ids are ignored. */
    int synchroniser() {
        try {
            List<Map<String, Object>> sensors = RestClient.create(iotUrl).get().uri("/api/sensors").retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            if (sensors == null) {
                return 0;
            }
            int updated = 0;
            for (Map<String, Object> s : sensors) {
                Object last = s.get("lastSeen");
                updated += jdbc.update("""
                        UPDATE capteur SET derniere_communication = ?, messages_recus = ?, messages_rejetes = ?
                        WHERE id = ?""",
                        last == null ? null : Timestamp.from(Instant.parse(last.toString())),
                        ((Number) s.getOrDefault("messageCount", 0)).longValue(),
                        ((Number) s.getOrDefault("rejectedCount", 0)).longValue(),
                        s.get("id"));
            }
            return updated;
        } catch (RuntimeException e) {
            log.warn("supervision: module 1 unreachable at {} ({})", iotUrl, e.getMessage());
            return 0;
        }
    }
}
