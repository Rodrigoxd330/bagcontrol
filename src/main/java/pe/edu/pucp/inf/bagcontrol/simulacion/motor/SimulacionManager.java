package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.ConfiguracionColapsoDTO;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.SimulacionEstadoDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class SimulacionManager {

    private final PlanificadorService planificadorService;
    private final AeropuertoRepository aeropuertoRepository;
    private final WebSocketPublisher webSocketPublisher;

    private final ConcurrentHashMap<String, SimulacionJob> trabajosActivos = new ConcurrentHashMap<>();

    public String crearJob(LocalDate fechaInicio, int k, String algoritmo, long saMs) {
        if (k <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La cantidad de dias debe ser mayor que 0.");
        }
        if (saMs < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La velocidad no puede ser negativa.");
        }

        String simulacionId = UUID.randomUUID().toString();
        SimulacionJob job = new SimulacionJob(
                simulacionId,
                fechaInicio,
                k,
                algoritmo,
                saMs,
                planificadorService,
                aeropuertoRepository,
                webSocketPublisher
        );

        trabajosActivos.put(simulacionId, job);
        Thread thread = new Thread(job, "simulacion-" + simulacionId);
        job.asignarHilo(thread);
        thread.start();

        return simulacionId;
    }

    public String crearJobColapso(
            LocalDate fechaInicio,
            String algoritmo,
            long saMs,
            ConfiguracionColapsoDTO configuracion
    ) {
        validarConfiguracionColapso(saMs, configuracion);

        String simulacionId = UUID.randomUUID().toString();
        SimulacionJob job = new SimulacionJob(
                simulacionId,
                fechaInicio,
                configuracion.getMaxDias(),
                algoritmo,
                saMs,
                planificadorService,
                aeropuertoRepository,
                webSocketPublisher,
                configuracion
        );

        trabajosActivos.put(simulacionId, job);
        Thread thread = new Thread(job, "simulacion-colapso-" + simulacionId);
        job.asignarHilo(thread);
        thread.start();

        return simulacionId;
    }

    private void validarConfiguracionColapso(long saMs, ConfiguracionColapsoDTO configuracion) {
        if (saMs < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La velocidad no puede ser negativa.");
        }
        if (configuracion.getMaxDias() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxDias debe ser mayor que 0.");
        }
        if (configuracion.getTamanoCicloDias() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tamanoCicloDias debe ser mayor que 0.");
        }
        if (configuracion.getUmbralSinItinerario() < 0 || configuracion.getUmbralSla() < 0
                || configuracion.getUmbralAeropuerto() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Los umbrales no pueden ser negativos.");
        }
        if (configuracion.getCiclosPendientesCrecientes() <= 0
                || configuracion.getCiclosSobrecargaVuelo() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Los ciclos consecutivos deben ser mayores que 0.");
        }
    }

    public void detenerJob(String simulacionId) {
        obtenerJob(simulacionId).detener();
    }

    public void pausarJob(String simulacionId) {
        obtenerJob(simulacionId).pausar();
    }

    public void reanudarJob(String simulacionId) {
        obtenerJob(simulacionId).reanudar();
    }

    public void cambiarVelocidad(String simulacionId, long saMs) {
        if (saMs < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La velocidad no puede ser negativa.");
        }
        obtenerJob(simulacionId).cambiarVelocidad(saMs);
    }

    public SimulacionEstadoDTO obtenerEstado(String simulacionId) {
        SimulacionJob job = obtenerJob(simulacionId);
        SimulacionState state = job.getState();

        return new SimulacionEstadoDTO(
                state.getSimulacionId(),
                state.getEstado(),
                job.estaPausada(),
                job.estaDetenida(),
                state.getVelocidadMs(),
                state.getUltimoLoteEmitidoNumero().get(),
                job.getAlgoritmo(),
                job.getK(),
                job.getFechaInicio().toString(),
                job.getFechaCreacion().toString(),
                state.getTiempoSimuladoActual() != null ? state.getTiempoSimuladoActual().toString() : null
        );
    }

    public SimulacionState obtenerState(String simulacionId) {
        return obtenerJob(simulacionId).getState();
    }

    public List<EnvioDTO> extraerEnviosPorVuelo(String simulacionId, Long codigoVuelo) {
        return obtenerState(simulacionId)
                .getEnviosPorVuelo()
                .getOrDefault(codigoVuelo, List.of());
    }

    public SolucionRuta obtenerPlanCompleto(String simulacionId) {
        SolucionRuta solucion = obtenerState(simulacionId).getSolucionActual();
        if (solucion == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El plan aun no ha sido generado.");
        }
        return solucion;
    }

    private SimulacionJob obtenerJob(String simulacionId) {
        SimulacionJob job = trabajosActivos.get(simulacionId);
        if (job == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No existe una simulacion activa con id: " + simulacionId
            );
        }
        return job;
    }
}
