package pe.edu.pucp.inf.bagcontrol.analytics.dashboard;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/api/dashboard/resumen")
    public DashboardResumenDTO obtenerResumenDashboard(
            @RequestParam("fechaInicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fechaInicio,
            @RequestParam("dias") int dias,
            @RequestParam(value = "algoritmo", defaultValue = "GRASP") String algoritmo
    ) {
        return dashboardService.obtenerResumenDashboard(fechaInicio, dias, algoritmo);
    }
}