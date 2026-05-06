package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.SimulacionEstadoDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class SimulacionController {

    private final SimulacionManager simulacionManager;

    @PostMapping("/api/simulacion/ws/iniciar")
    public Map<String, String> iniciarSimulacion(
            @RequestParam("fechaInicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate fechaInicio,
            @RequestParam("k")
            int k,
            @RequestParam(value = "algoritmo", defaultValue = "TABU")
            String algoritmo,
            @RequestParam(value = "saMs", defaultValue = "1000")
            long saMs
    ) {
        String simulacionId = simulacionManager.crearJob(fechaInicio, k, algoritmo, saMs);

        return Map.of(
                "simulacionId", simulacionId,
                "topic", "/topic/simulacion/" + simulacionId + "/eventos"
        );
    }

    @PostMapping("/api/simulacion/ws/{simulacionId}/pausar")
    public Map<String, String> pausar(@PathVariable String simulacionId) {
        simulacionManager.pausarJob(simulacionId);

        return Map.of(
                "simulacionId", simulacionId,
                "estado", "PAUSADA",
                "mensaje", "Simulacion pausada correctamente"
        );
    }

    @PostMapping("/api/simulacion/ws/{simulacionId}/reanudar")
    public Map<String, String> reanudar(@PathVariable String simulacionId) {
        simulacionManager.reanudarJob(simulacionId);

        return Map.of(
                "simulacionId", simulacionId,
                "estado", "EN_PROCESO",
                "mensaje", "Simulacion reanudada correctamente"
        );
    }

    @PostMapping("/api/simulacion/ws/{simulacionId}/detener")
    public Map<String, String> detener(@PathVariable String simulacionId) {
        simulacionManager.detenerJob(simulacionId);

        return Map.of(
                "simulacionId", simulacionId,
                "estado", "DETENIDA",
                "mensaje", "Simulacion detenida correctamente"
        );
    }

    @PostMapping("/api/simulacion/ws/{simulacionId}/velocidad")
    public Map<String, Object> cambiarVelocidad(
            @PathVariable String simulacionId,
            @RequestParam("saMs") long saMs
    ) {
        simulacionManager.cambiarVelocidad(simulacionId, saMs);

        return Map.of(
                "simulacionId", simulacionId,
                "saMs", saMs,
                "mensaje", "Velocidad actualizada correctamente"
        );
    }

    @GetMapping("/api/simulacion/ws/{simulacionId}/estado")
    public SimulacionEstadoDTO obtenerEstado(@PathVariable String simulacionId) {
        return simulacionManager.obtenerEstado(simulacionId);
    }

    @GetMapping("/api/simulacion/ws/{simulacionId}/vuelos/{codigoVuelo}/envios")
    public List<EnvioDTO> obtenerEnviosPorVuelo(
            @PathVariable String simulacionId,
            @PathVariable Long codigoVuelo
    ) {
        return simulacionManager.extraerEnviosPorVuelo(simulacionId, codigoVuelo);
    }

    @GetMapping("/api/simulacion/ws/{simulacionId}/plan")
    public SolucionRuta obtenerPlan(@PathVariable String simulacionId) {
        return simulacionManager.obtenerPlanCompleto(simulacionId);
    }
}
