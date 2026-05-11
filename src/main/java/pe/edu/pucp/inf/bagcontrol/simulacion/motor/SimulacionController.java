package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.SimulacionEstadoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.out.RespuestaInicioSimulacionDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/simulacion")
@RequiredArgsConstructor
public class SimulacionController {

    private final SimulacionManager simulacionManager;

    /**
     * Endpoint para iniciar simulaciones.
     * Escenario operación dia a dia: K = 1
     * Escenario 5 días: Fecha fin - fecha inicio = 5 días
     * Esenario colapso: Fecha Fin = null
     */
    @PostMapping("/preparar")
    public RespuestaInicioSimulacionDTO preparaSimulacion(
            @RequestParam("fechaInicio")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaInicio,

            @RequestParam(value = "fechaFin", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fechaFin,

            @RequestParam(value = "k", defaultValue = "15") int k,

            @RequestParam(value = "algoritmo", defaultValue = "TABU") String algoritmo
    ) {
        String simulacionId = simulacionManager.crearJob(fechaInicio, fechaFin, k, algoritmo);
        String modo = (fechaFin == null) ? "COLAPSO" : "ESTANDAR";
        String topic = "/topic/simulacion/" + simulacionId + "/eventos";

        return new RespuestaInicioSimulacionDTO(simulacionId, topic, modo);
    }

    @PostMapping("/iniciar/{simulacionId}/arrancar")
    public Map<String, String> iniciarSimulacion(@PathVariable String simulacionId) {
        simulacionManager.arrancarJob(simulacionId);
        return Map.of("mensaje", "Simulación en marcha");
    }



    @PostMapping("/{simulacionId}/pausar")
    public Map<String, String> pausar(@PathVariable String simulacionId) {
        simulacionManager.pausarJob(simulacionId);
        return Map.of("estado", "PAUSADA", "mensaje", "Simulación pausada");
    }

    @PostMapping("/{simulacionId}/reanudar")
    public Map<String, String> reanudar(@PathVariable String simulacionId) {
        simulacionManager.reanudarJob(simulacionId);
        return Map.of("estado", "EN_PROCESO", "mensaje", "Simulación reanudada");
    }

    @PostMapping("/{simulacionId}/detener")
    public Map<String, String> detener(@PathVariable String simulacionId) {
        simulacionManager.detenerJob(simulacionId);
        return Map.of("estado", "DETENIDA", "mensaje", "Simulación abortada");
    }

    // Opcional: Solo si implementas botón de cámara rápida en el front
    @PostMapping("/{simulacionId}/velocidad")
    public Map<String, String> cambiarVelocidad(
            @PathVariable String simulacionId,
            @RequestParam("multiplicador") int multiplicador // ej: 1x, 2x, 5x
    ) {
        simulacionManager.cambiarVelocidad(simulacionId, multiplicador);
        return Map.of("mensaje", "Velocidad actualizada a " + multiplicador + "x");
    }

    @GetMapping("/{simulacionId}/estado")
    public SimulacionEstadoDTO obtenerEstado(@PathVariable String simulacionId) {
        return simulacionManager.obtenerEstado(simulacionId);
    }

    @GetMapping("/{simulacionId}/vuelos/{codigoVuelo}/envios")
    public List<EnvioDTO> obtenerEnviosPorVuelo(
            @PathVariable String simulacionId,
            @PathVariable Long codigoVuelo
    ) {
        return simulacionManager.extraerEnviosPorVuelo(simulacionId, codigoVuelo);
    }
}