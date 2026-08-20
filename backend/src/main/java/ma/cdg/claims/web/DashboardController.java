package ma.cdg.claims.web;

import ma.cdg.claims.service.DashboardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Figures for the home dashboard. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/stats")
    public DashboardService.DashboardStats stats(@RequestParam(defaultValue = "30") int days) {
        return dashboard.stats(Math.clamp(days, 7, 180));
    }
}
