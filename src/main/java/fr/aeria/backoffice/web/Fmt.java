package fr.aeria.backoffice.web;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

/** Formatting helpers for the templates: {@code ${@fmt.statut(t.statut)}}, {@code ${@fmt.date(x)}}. */
@Component("fmt")
public class Fmt {

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.FRANCE).withZone(ZONE);
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.FRANCE).withZone(ZONE);
    private static final DateTimeFormatter M = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE).withZone(ZONE);

    private static final Map<String, String> STATUTS = Map.ofEntries(
            Map.entry("ouvert", "Ouvert"), Map.entry("assigne", "Assigné"), Map.entry("en_cours", "En cours"),
            Map.entry("resolu", "Résolu - à valider"), Map.entry("cloture", "Clôturé"), Map.entry("annule", "Annulé"),
            Map.entry("en_stock", "En stock"), Map.entry("en_service", "En service"),
            Map.entry("en_maintenance", "En maintenance"), Map.entry("hors_service", "Hors service"),
            Map.entry("reforme", "Réformé"), Map.entry("reussie", "Réussie"), Map.entry("partielle", "Partielle"),
            Map.entry("echec", "Échec"), Map.entry("corrective", "Corrective"), Map.entry("preventive", "Préventive"),
            Map.entry("installation", "Installation"), Map.entry("depose", "Dépose"), Map.entry("auto", "Supervision"),
            Map.entry("manuel", "Manuel"), Map.entry("preventif", "Préventif"));

    public String statut(Object code) {
        return code == null ? "" : STATUTS.getOrDefault(code.toString(), code.toString());
    }

    public String priorite(Object p) {
        if (p == null) {
            return "";
        }
        return switch (((Number) p).intValue()) {
            case 1 -> "P1 urgente";
            case 2 -> "P2 haute";
            default -> "P3 normale";
        };
    }

    public String date(Object value) {
        return format(value, D);
    }

    public String dateHeure(Object value) {
        return format(value, DT);
    }

    public String mois(Object value) {
        String s = format(value, M);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    public String nombre(Object value) {
        if (value == null) {
            return "-";
        }
        if (value instanceof BigDecimal b) {
            return b.stripTrailingZeros().toPlainString().replace('.', ',');
        }
        return value.toString().replace('.', ',');
    }

    private static String format(Object value, DateTimeFormatter f) {
        if (value == null) {
            return "-";
        }
        if (value instanceof java.sql.Date d) {
            return f.format(d.toLocalDate().atStartOfDay(ZONE));
        }
        if (value instanceof Date d) {
            return f.format(d.toInstant());
        }
        if (value instanceof TemporalAccessor t) {
            return f.format(t);
        }
        return value.toString();
    }
}
