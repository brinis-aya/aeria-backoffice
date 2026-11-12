package fr.aeria.backoffice.web;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import fr.aeria.backoffice.config.AeriaUser;
import fr.aeria.backoffice.db.BusinessRuleException;
import fr.aeria.backoffice.repo.ParcRepository;
import fr.aeria.backoffice.repo.TicketRepository;
import fr.aeria.backoffice.repo.TicketRepository.PieceLine;

/** Ticket life cycle, following the BPMN « Maintenance corrective » process. */
@Controller
@RequestMapping("/tickets")
public class TicketController {

    private static final Set<String> FILTRES = Set.of("actifs", "hors-delai", "a-valider", "clos", "tous", "mes");

    private final TicketRepository tickets;
    private final ParcRepository parc;

    public TicketController(TicketRepository tickets, ParcRepository parc) {
        this.tickets = tickets;
        this.parc = parc;
    }

    @GetMapping
    public String liste(@RequestParam(defaultValue = "actifs") String filtre, @AuthenticationPrincipal AeriaUser user,
                        Model model) {
        String f = FILTRES.contains(filtre) ? filtre : "actifs";
        model.addAttribute("filtre", f);
        model.addAttribute("tickets", tickets.list(f, user.getTechnicienId()));
        return "tickets";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable int id, Model model) {
        Map<String, Object> t = tickets.detail(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        model.addAttribute("t", t);
        model.addAttribute("historique", tickets.historique(id));
        model.addAttribute("interventions", tickets.interventions(id));
        model.addAttribute("propositions", tickets.propositions(id));
        model.addAttribute("pieces", tickets.pieces());
        model.addAttribute("demain9h", LocalDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0));
        return "ticket";
    }

    @GetMapping("/nouveau")
    public String formulaire(@RequestParam(required = false) String capteur, Model model) {
        model.addAttribute("capteurs", parc.capteursSurSite());
        model.addAttribute("types", parc.typesIncident());
        model.addAttribute("capteurChoisi", capteur);
        return "ticket-nouveau";
    }

    @PostMapping("/nouveau")
    public String creer(@AuthenticationPrincipal AeriaUser user, @RequestParam String capteur, @RequestParam String type,
                        @RequestParam(required = false) Integer priorite, @RequestParam String titre,
                        @RequestParam(required = false) String description, RedirectAttributes ra) {
        try {
            int id = tickets.create(user.getUsername(), capteur, type, priorite, titre.strip(),
                    description == null || description.isBlank() ? null : description.strip());
            ra.addFlashAttribute("ok", "Ticket n° " + id + " créé.");
            return "redirect:/tickets/" + id;
        } catch (BusinessRuleException e) {
            ra.addFlashAttribute("erreur", e.getMessage());
            return "redirect:/tickets/nouveau?capteur=" + capteur;
        }
    }

    @PostMapping("/{id}/planifier")
    public String planifier(@PathVariable int id, @AuthenticationPrincipal AeriaUser user, @RequestParam int technicien,
                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime quand,
                            RedirectAttributes ra) {
        return WebAdvice.action(ra, "/tickets/" + id, "Intervention planifiée.",
                () -> tickets.planifier(user.getUsername(), id, technicien, quand));
    }

    @PostMapping("/{id}/interventions/{iv}/demarrer")
    public String demarrer(@PathVariable int id, @PathVariable int iv, @AuthenticationPrincipal AeriaUser user,
                           RedirectAttributes ra) {
        return WebAdvice.action(ra, "/tickets/" + id, "Intervention démarrée.", () -> tickets.demarrer(user, iv));
    }

    @PostMapping("/{id}/interventions/{iv}/terminer")
    public String terminer(@PathVariable int id, @PathVariable int iv, @AuthenticationPrincipal AeriaUser user,
                           @RequestParam String resultat, @RequestParam String compteRendu,
                           @RequestParam(required = false) List<Integer> piece,
                           @RequestParam(required = false) List<Integer> quantite, RedirectAttributes ra) {
        if (!Set.of("reussie", "partielle", "echec").contains(resultat) || compteRendu.isBlank()) {
            ra.addFlashAttribute("erreur", "Le résultat et le compte rendu sont obligatoires.");
            return "redirect:/tickets/" + id;
        }
        List<PieceLine> lignes = new ArrayList<>();
        if (piece != null && quantite != null) {
            for (int i = 0; i < Math.min(piece.size(), quantite.size()); i++) {
                if (piece.get(i) != null && quantite.get(i) != null && quantite.get(i) > 0) {
                    lignes.add(new PieceLine(piece.get(i), quantite.get(i)));
                }
            }
        }
        String msg = "reussie".equals(resultat) ? "Intervention terminée, ticket résolu : en attente de validation."
                : "Intervention terminée sans succès : une nouvelle intervention doit être planifiée.";
        return WebAdvice.action(ra, "/tickets/" + id, msg,
                () -> tickets.terminer(user, iv, resultat, compteRendu.strip(), lignes));
    }

    @PostMapping("/{id}/cloturer")
    public String cloturer(@PathVariable int id, @AuthenticationPrincipal AeriaUser user, RedirectAttributes ra) {
        return WebAdvice.action(ra, "/tickets/" + id, "Ticket clôturé.",
                () -> tickets.changerStatut(user.getUsername(), id, "cloture"));
    }

    @PostMapping("/{id}/rouvrir")
    public String rouvrir(@PathVariable int id, @AuthenticationPrincipal AeriaUser user, RedirectAttributes ra) {
        return WebAdvice.action(ra, "/tickets/" + id, "Ticket rouvert : la réparation n'est pas validée.",
                () -> tickets.changerStatut(user.getUsername(), id, "en_cours"));
    }

    @PostMapping("/{id}/annuler")
    public String annuler(@PathVariable int id, @AuthenticationPrincipal AeriaUser user, RedirectAttributes ra) {
        return WebAdvice.action(ra, "/tickets/" + id, "Ticket annulé.",
                () -> tickets.changerStatut(user.getUsername(), id, "annule"));
    }
}
