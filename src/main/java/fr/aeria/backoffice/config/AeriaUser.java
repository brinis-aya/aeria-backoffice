package fr.aeria.backoffice.config;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** Authenticated user; technicians carry their technician id to filter "their" interventions. */
public class AeriaUser extends User {

    private final Integer technicienId;

    public AeriaUser(String login, String hash, boolean active, String role, Integer technicienId) {
        this(login, hash, active, true, role, technicienId);
    }

    public AeriaUser(String login, String hash, boolean active, boolean notLocked, String role, Integer technicienId) {
        super(login, hash, active, true, true, notLocked, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.technicienId = technicienId;
    }

    public Integer getTechnicienId() {
        return technicienId;
    }

    public boolean isResponsable() {
        return technicienId == null;
    }
}
