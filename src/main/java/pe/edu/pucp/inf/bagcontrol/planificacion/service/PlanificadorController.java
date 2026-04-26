package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.ResultadoSimulacionDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.PlanResultadoDTO;
import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
public class PlanificadorController {

    private final PlanificadorService planificadorService;

    @GetMapping("/api/simulacion/resumen")
    public ResultadoSimulacionDTO obtenerResumenSimulacion(
            @RequestParam("fechaInicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fechaInicio,
            @RequestParam("dias")
            int dias
    ) {
        return planificadorService.ejecutarSimulacion(fechaInicio, dias);
    }
    @GetMapping("/api/simulacion/plan")
    public PlanResultadoDTO obtenerPlan(
            @RequestParam("algoritmo") String algoritmo,
            @RequestParam("fechaInicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fechaInicio,
            @RequestParam("dias") int dias
    ) {
        return planificadorService.obtenerPlan(algoritmo, fechaInicio, dias);
    }
}