package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SimulacionEstadoDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.SimulacionWebSocketService;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Deprecated
@RequiredArgsConstructor
public class SimulacionWebSocketController {

    private final SimulacionWebSocketService simulacionWebSocketService;

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
        String simulacionId = UUID.randomUUID().toString();

        // Primero se registra la simulación en memoria
        simulacionWebSocketService.registrarSimulacion(
                simulacionId,
                fechaInicio,
                k,
                algoritmo,
                saMs
        );

        // Luego se lanza la simulación asíncrona
        simulacionWebSocketService.ejecutarSimulacionRealtime(
                simulacionId,
                fechaInicio,
                k,
                algoritmo,
                saMs
        );

        return Map.of(
                "simulacionId", simulacionId,
                "topic", "/topic/simulacion/" + simulacionId
        );
    }

    @PostMapping("/api/simulacion/ws/{simulacionId}/pausar")
    public Map<String, String> pausar(@PathVariable String simulacionId) {
        simulacionWebSocketService.pausarSimulacion(simulacionId);

        return Map.of(
                "simulacionId", simulacionId,
                "estado", "PAUSADA",
                "mensaje", "Simulación pausada correctamente"
        );
    }

    @PostMapping("/api/simulacion/ws/{simulacionId}/reanudar")
    public Map<String, String> reanudar(@PathVariable String simulacionId) {
        simulacionWebSocketService.reanudarSimulacion(simulacionId);

        return Map.of(
                "simulacionId", simulacionId,
                "estado", "EN_PROCESO",
                "mensaje", "Simulación reanudada correctamente"
        );
    }

    @PostMapping("/api/simulacion/ws/{simulacionId}/detener")
    public Map<String, String> detener(@PathVariable String simulacionId) {
        simulacionWebSocketService.detenerSimulacion(simulacionId);

        return Map.of(
                "simulacionId", simulacionId,
                "estado", "DETENIDA",
                "mensaje", "Simulación detenida correctamente"
        );
    }

    @PostMapping("/api/simulacion/ws/{simulacionId}/velocidad")
    public Map<String, Object> cambiarVelocidad(
            @PathVariable String simulacionId,
            @RequestParam("saMs") long saMs
    ) {
        simulacionWebSocketService.cambiarVelocidad(simulacionId, saMs);

        return Map.of(
                "simulacionId", simulacionId,
                "saMs", saMs,
                "mensaje", "Velocidad actualizada correctamente"
        );
    }

    @GetMapping("/api/simulacion/ws/{simulacionId}/estado")
    public SimulacionEstadoDTO obtenerEstado(@PathVariable String simulacionId) {
        return simulacionWebSocketService.obtenerEstado(simulacionId);
    }
}
