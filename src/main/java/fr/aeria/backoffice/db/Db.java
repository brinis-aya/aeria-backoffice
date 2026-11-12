package fr.aeria.backoffice.db;

import java.sql.SQLException;
import java.util.function.Supplier;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a unit of work in one transaction, on behalf of a user.
 *
 * <p>The login is passed to PostgreSQL ({@code SET LOCAL aeria.utilisateur}) so that the triggers
 * record who did what (RG12). Errors raised by the business rules are translated into
 * {@link BusinessRuleException} with a message the user can act on.
 */
@Component
public class Db {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public Db(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public <T> T asUser(String login, Supplier<T> work) {
        try {
            return tx.execute(status -> {
                jdbc.queryForObject("SELECT set_config('aeria.utilisateur', ?, true)", String.class, login);
                return work.get();
            });
        } catch (DataAccessException e) {
            throw new BusinessRuleException(explain(e));
        }
    }

    public void asUser(String login, Runnable work) {
        asUser(login, () -> {
            work.run();
            return null;
        });
    }

    /** User-facing explanation of a database refusal. */
    static String explain(DataAccessException e) {
        Throwable t = e;
        while (t != null && !(t instanceof SQLException)) {
            t = t.getCause();
        }
        if (!(t instanceof PSQLException psql) || psql.getServerErrorMessage() == null) {
            return "Opération refusée par la base de données.";
        }
        ServerErrorMessage m = psql.getServerErrorMessage();
        String constraint = m.getConstraint() == null ? "" : m.getConstraint();
        return switch (String.valueOf(psql.getSQLState())) {
            case "P0001" -> m.getMessage();  // RAISE EXCEPTION in a trigger: already phrased for users
            case "23P01" -> constraint.startsWith("intervention")
                    ? "RG13 : ce technicien a déjà une intervention sur ce créneau."
                    : "RG2 : ce capteur est déjà installé sur un autre site pendant cette période.";
            case "23505" -> constraint.equals("ux_ticket_actif")
                    ? "RG3 : un ticket du même type est déjà ouvert pour ce capteur."
                    : "Cette valeur existe déjà (" + constraint + ").";
            case "23514" -> "Valeur refusée par la contrainte « " + constraint + " ».";
            case "23503" -> "Référence inconnue (" + constraint + ").";
            default -> m.getMessage();
        };
    }
}
