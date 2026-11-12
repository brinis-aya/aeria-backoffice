package fr.aeria.backoffice.web;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import fr.aeria.backoffice.config.AeriaUser;
import fr.aeria.backoffice.repo.ParcRepository;
import fr.aeria.backoffice.repo.PilotageRepository;
import fr.aeria.backoffice.service.SupervisionService;

/** Read-only pages: dashboard, fleet, technicians, stock. */
@Controller
public class PagesController {

    private final PilotageRepository pilotage;
    private final ParcRepository parc;
    private final SupervisionService supervision;

    public PagesController(PilotageRepository pilotage, ParcRepository parc, SupervisionService supervision) {
        this.pilotage = pilotage;
        this.parc = parc;
        this.supervision = supervision;
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }

    @GetMapping("/")
    public String accueil(@AuthenticationPrincipal AeriaUser user, Model model) {
        model.addAttribute("kpi", pilotage.indicateurs());
        model.addAttribute("urgents", pilotage.urgents());
        model.addAttribute("mensuel", pilotage.kpiMensuel());
        model.addAttribute("aFaire", pilotage.interventionsAFaire(user.getTechnicienId()));
        return "accueil";
    }

    @GetMapping("/capteurs")
    public String capteurs(Model model) {
        model.addAttribute("capteurs", parc.capteurs());
        return "capteurs";
    }

    @GetMapping("/capteurs/{id}")
    public String capteur(@PathVariable String id, Model model) {
        model.addAttribute("c", parc.capteur(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
        model.addAttribute("installations", parc.installations(id));
        model.addAttribute("tickets", parc.tickets(id));
        model.addAttribute("etalonnages", parc.etalonnages(id));
        return "capteur";
    }

    @GetMapping("/techniciens")
    public String techniciens(Model model) {
        model.addAttribute("techniciens", pilotage.techniciens());
        model.addAttribute("aFaire", pilotage.interventionsAFaire(null));
        return "techniciens";
    }

    @GetMapping("/stock")
    public String stock(Model model) {
        model.addAttribute("pieces", pilotage.stock());
        model.addAttribute("couts", pilotage.couts());
        return "stock";
    }

    /** Manual run of the supervision (demo, or after an outage of module 1). */
    @PostMapping("/supervision/executer")
    public String executerSupervision(RedirectAttributes ra) {
        int[] n = new int[2];
        String r = WebAdvice.action(ra, "/tickets", "", () -> {
            n[0] = supervision.detecter();
            n[1] = supervision.planifierEtalonnages();
        });
        if (ra.getFlashAttributes().containsKey("ok")) {
            ra.addFlashAttribute("ok", "Supervision exécutée : " + n[0] + " anomalie(s), " + n[1]
                    + " étalonnage(s) planifié(s).");
        }
        return r;
    }
}
