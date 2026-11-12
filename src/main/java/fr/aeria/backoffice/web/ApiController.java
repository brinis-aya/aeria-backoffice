package fr.aeria.backoffice.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.aeria.backoffice.repo.ParcRepository;
import fr.aeria.backoffice.repo.PilotageRepository;
import fr.aeria.backoffice.repo.TicketRepository;

/** Read-only JSON for the dashboard (module 6). Authenticated (session or HTTP Basic). */
@RestController
@RequestMapping("/api")
public class ApiController {

    private final ParcRepository parc;
    private final PilotageRepository pilotage;
    private final TicketRepository tickets;

    public ApiController(ParcRepository parc, PilotageRepository pilotage, TicketRepository tickets) {
        this.parc = parc;
        this.pilotage = pilotage;
        this.tickets = tickets;
    }

    @GetMapping("/capteurs")
    public List<Map<String, Object>> capteurs() {
        return parc.capteurs();
    }

    @GetMapping("/indicateurs")
    public Map<String, Object> indicateurs() {
        return pilotage.indicateurs();
    }

    @GetMapping("/tickets/actifs")
    public List<Map<String, Object>> ticketsActifs() {
        return tickets.list("actifs", null);
    }
}
