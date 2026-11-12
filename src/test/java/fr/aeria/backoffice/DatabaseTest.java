package fr.aeria.backoffice;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import fr.aeria.backoffice.db.Db;

/** Shared context: a real PostgreSQL rebuilt once per run (migrations + demo year). */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseConfig.class)
abstract class DatabaseTest {

    static final String USER = "test";

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    Db db;

    /** A sensor in service, installed, with no active ticket of this type. */
    String capteurLibre(String type) {
        return jdbc.queryForObject("""
                SELECT c.id FROM capteur c
                WHERE c.statut = 'en_service'
                  AND EXISTS (SELECT 1 FROM installation i WHERE i.capteur_id = c.id AND i.date_depose IS NULL)
                  AND NOT EXISTS (SELECT 1 FROM ticket t WHERE t.capteur_id = c.id AND t.type_code = ?
                                  AND t.statut IN ('ouvert', 'assigne', 'en_cours'))
                ORDER BY c.id LIMIT 1""", String.class, type);
    }

    int ticket(String capteur, String type) {
        return db.asUser(USER, () -> jdbc.queryForObject("""
                INSERT INTO ticket (capteur_id, type_code, origine, titre) VALUES (?, ?, 'manuel', 'test') RETURNING id""",
                Integer.class, capteur, type));
    }

    int intervention(int ticket, int technicien, LocalDateTime planifiee) {
        return db.asUser(USER, () -> jdbc.queryForObject("""
                INSERT INTO intervention (ticket_id, technicien_id, nature, planifiee_le)
                VALUES (?, ?, 'corrective', ?) RETURNING id""",
                Integer.class, ticket, technicien, Timestamp.valueOf(planifiee)));
    }

    void sql(String statement, Object... args) {
        db.asUser(USER, () -> jdbc.update(statement, args));
    }

    String statut(int ticket) {
        return jdbc.queryForObject("SELECT statut FROM ticket WHERE id = ?", String.class, ticket);
    }
}
