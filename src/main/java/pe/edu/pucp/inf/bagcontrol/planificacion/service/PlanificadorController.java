package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.ResultadoSimulacionDTO;

@RestController
@RequiredArgsConstructor
public class PlanificadorController {

    private final PlanificadorService planificadorService;

    @GetMapping("/api/simulacion/resumen")
    public ResultadoSimulacionDTO obtenerResumenSimulacion() {
        return planificadorService.ejecutarPrueba();
    }
}