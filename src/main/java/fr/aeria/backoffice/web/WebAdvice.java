package fr.aeria.backoffice.web;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import fr.aeria.backoffice.config.AeriaUser;
import fr.aeria.backoffice.db.BusinessRuleException;

/** Exposes the current user to every page and offers a helper for form actions. */
@ControllerAdvice(basePackages = "fr.aeria.backoffice.web")
public class WebAdvice {

    @ModelAttribute
    public void currentUser(@AuthenticationPrincipal AeriaUser user, Model model) {
        if (user != null) {
            model.addAttribute("moi", user);
            model.addAttribute("responsable", user.isResponsable());
        }
    }

    /** Runs a write action and reports its outcome as a flash message on the next page. */
    static String action(RedirectAttributes ra, String redirect, String success, Runnable work) {
        try {
            work.run();
            ra.addFlashAttribute("ok", success);
        } catch (BusinessRuleException e) {
            ra.addFlashAttribute("erreur", e.getMessage());
        }
        return "redirect:" + redirect;
    }
}
