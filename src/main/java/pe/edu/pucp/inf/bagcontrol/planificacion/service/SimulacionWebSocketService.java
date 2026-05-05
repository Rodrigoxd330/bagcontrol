package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EventoSimulacionDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SimulacionControl;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SimulacionEstadoDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SimulacionVueloAgrupado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SimulacionWebSocketService {

    private final SimpMessagingTemplate messagingTemplate;
    private final PlanificadorService planificadorService;
    private final AeropuertoRepository aeropuertoRepository;

    private final Map<String, SimulacionControl> simulacionesActivas = new ConcurrentHashMap<>();

    public void registrarSimulacion(
            String simulacionId,
            LocalDate fechaInicio,
            int k,
            String algoritmo,
            long saMs
    ) {
        if (saMs < 0) {
            throw new IllegalArgumentException("La velocidad no puede ser negativa.");
        }

        SimulacionControl control = new SimulacionControl(simulacionId);
        control.setAlgoritmo(algoritmo);
        control.setK(k);
        control.setFechaInicio(fechaInicio.toString());
        control.cambiarVelocidad(saMs);
        control.setEstado("EN_PROCESO");

        simulacionesActivas.put(simulacionId, control);
    }

    @Async
    public void ejecutarSimulacionRealtime(
            String simulacionId,
            LocalDate fechaInicio,
            int k,
            String algoritmo,
            long saMs
    ) {
        String topic = "/topic/simulacion/" + simulacionId;
        SimulacionControl control = obtenerControl(simulacionId);

        try {
            // Espera corta para que el frontend pueda suscribirse al topic.
            dormir(700);

            if (control.estaDetenida()) {
                enviarSimulacionDetenida(topic, control);
                return;
            }

            enviar(topic, control, EventoSimulacionDTO.builder()
                    .simulacionId(simulacionId)
                    .tipo("SIMULACION_INICIADA")
                    .mensaje("Simulación iniciada con K=" + k + " usando " + algoritmo)
                    .estado("EN_PROCESO")
                    .extra(Map.of(
                            "fechaInicio", fechaInicio.toString(),
                            "k", k,
                            "algoritmo", algoritmo,
                            "saMs", control.getVelocidadMs()
                    ))
                    .build());

            List<Aeropuerto> aeropuertos = aeropuertoRepository.findAll();

            Map<String, Aeropuerto> mapaAeropuertos = aeropuertos.stream()
                    .collect(Collectors.toMap(Aeropuerto::getCodigoIata, a -> a));

            Map<String, Integer> maletasPorAeropuerto = new HashMap<>();

            // Estado inicial de aeropuertos
            for (Aeropuerto aeropuerto : aeropuertos) {
                esperarSiPausadaODetenida(control, topic);

                maletasPorAeropuerto.put(aeropuerto.getCodigoIata(), 0);

                enviar(topic, control, crearEventoAeropuerto(
                        simulacionId,
                        aeropuerto,
                        0,
                        "Estado inicial del aeropuerto " + aeropuerto.getCodigoIata()
                ));
            }

            SolucionRuta solucion = planificadorService.calcularSolucion(
                    algoritmo,
                    fechaInicio,
                    k
            );

            cargarMaletasInicialesEnOrigenes(solucion, maletasPorAeropuerto);

            // Carga inicial de maletas en aeropuertos origen
            for (Aeropuerto aeropuerto : aeropuertos) {
                esperarSiPausadaODetenida(control, topic);

                int cantidad = maletasPorAeropuerto.getOrDefault(aeropuerto.getCodigoIata(), 0);

                enviar(topic, control, crearEventoAeropuerto(
                        simulacionId,
                        aeropuerto,
                        cantidad,
                        "Carga inicial del aeropuerto " + aeropuerto.getCodigoIata()
                ));
            }

            List<SimulacionVueloAgrupado> vuelosAgrupados = agruparVuelos(solucion);

            enviar(topic, control, EventoSimulacionDTO.builder()
                    .simulacionId(simulacionId)
                    .tipo("PLAN_GENERADO")
                    .mensaje("Plan generado con " + vuelosAgrupados.size() + " vuelos agrupados")
                    .estado("PLANIFICADO")
                    .extra(Map.of(
                            "fitness", solucion.getFitness(),
                            "totalAsignaciones", solucion.getAsignaciones().size(),
                            "sinItinerario", solucion.getSinItinerarioCount(),
                            "excedeSla", solucion.getExcedeSlaCount(),
                            "vuelosAgrupados", vuelosAgrupados.size()
                    ))
                    .build());

            // Eventos de vuelos
            for (SimulacionVueloAgrupado vuelo : vuelosAgrupados) {
                esperarSiPausadaODetenida(control, topic);

                String origen = vuelo.getOrigenIata();
                String destino = vuelo.getDestinoIata();
                int cantidadMaletas = vuelo.getCantidadMaletas();

                // Despegue: actualizar aeropuerto origen
                restarMaletas(maletasPorAeropuerto, origen, cantidadMaletas);

                Aeropuerto aeropuertoOrigen = mapaAeropuertos.get(origen);

                if (aeropuertoOrigen != null) {
                    enviar(topic, control, crearEventoAeropuerto(
                            simulacionId,
                            aeropuertoOrigen,
                            maletasPorAeropuerto.getOrDefault(origen, 0),
                            "Aeropuerto " + origen + " actualizado por despegue"
                    ));
                }

                enviar(topic, control, EventoSimulacionDTO.builder()
                        .simulacionId(simulacionId)
                        .tipo("VUELO_DESPEGA")
                        .mensaje("Vuelo " + vuelo.getCodigoVuelo() + " despegó de " + origen)
                        .codigoVuelo(vuelo.getCodigoVuelo())
                        .origenIata(origen)
                        .destinoIata(destino)
                        .horaSalida(vuelo.getFechaHoraSalida().toString())
                        .horaLlegada(vuelo.getFechaHoraLlegada().toString())
                        .cantidadMaletas(cantidadMaletas)
                        .estado("EN_VUELO")
                        .build());

                esperarConControl(control, topic);

                // Aterrizaje: actualizar aeropuerto destino
                sumarMaletas(maletasPorAeropuerto, destino, cantidadMaletas);

                enviar(topic, control, EventoSimulacionDTO.builder()
                        .simulacionId(simulacionId)
                        .tipo("VUELO_ATERRIZA")
                        .mensaje("Vuelo " + vuelo.getCodigoVuelo() + " aterrizó en " + destino)
                        .codigoVuelo(vuelo.getCodigoVuelo())
                        .origenIata(origen)
                        .destinoIata(destino)
                        .horaSalida(vuelo.getFechaHoraSalida().toString())
                        .horaLlegada(vuelo.getFechaHoraLlegada().toString())
                        .cantidadMaletas(cantidadMaletas)
                        .estado("ATERRIZADO")
                        .build());

                Aeropuerto aeropuertoDestino = mapaAeropuertos.get(destino);

                if (aeropuertoDestino != null) {
                    enviar(topic, control, crearEventoAeropuerto(
                            simulacionId,
                            aeropuertoDestino,
                            maletasPorAeropuerto.getOrDefault(destino, 0),
                            "Aeropuerto " + destino + " actualizado por aterrizaje"
                    ));
                }

                esperarConControl(control, topic);
            }

            control.setEstado("FINALIZADA");

            enviar(topic, control, EventoSimulacionDTO.builder()
                    .simulacionId(simulacionId)
                    .tipo("SIMULACION_FINALIZADA")
                    .mensaje("Simulación finalizada correctamente")
                    .estado("FINALIZADO")
                    .extra(Map.of(
                            "algoritmo", algoritmo,
                            "k", k,
                            "fitness", solucion.getFitness(),
                            "totalAsignaciones", solucion.getAsignaciones().size(),
                            "sinItinerario", solucion.getSinItinerarioCount(),
                            "excedeSla", solucion.getExcedeSlaCount(),
                            "totalEventos", control.getUltimoEventoEmitido().get()
                    ))
                    .build());

        } catch (SimulacionDetenidaException e) {
            enviarSimulacionDetenida(topic, control);

        } catch (Exception e) {
            control.setEstado("ERROR");

            enviar(topic, control, EventoSimulacionDTO.builder()
                    .simulacionId(simulacionId)
                    .tipo("ERROR")
                    .mensaje("Error durante la simulación: " + e.getMessage())
                    .estado("ERROR")
                    .extra(Map.of(
                            "error", e.getClass().getSimpleName()
                    ))
                    .build());
        }
    }

    public void pausarSimulacion(String simulacionId) {
        SimulacionControl control = obtenerControl(simulacionId);

        if ("FINALIZADA".equals(control.getEstado()) || "DETENIDA".equals(control.getEstado())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No se puede pausar una simulación finalizada o detenida."
            );
        }

        control.pausar();

        enviar("/topic/simulacion/" + simulacionId, control, EventoSimulacionDTO.builder()
                .simulacionId(simulacionId)
                .tipo("SIMULACION_PAUSADA")
                .mensaje("Simulación pausada")
                .estado("PAUSADA")
                .build());
    }

    public void reanudarSimulacion(String simulacionId) {
        SimulacionControl control = obtenerControl(simulacionId);

        if ("FINALIZADA".equals(control.getEstado()) || "DETENIDA".equals(control.getEstado())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "No se puede reanudar una simulación finalizada o detenida."
            );
        }

        control.reanudar();

        enviar("/topic/simulacion/" + simulacionId, control, EventoSimulacionDTO.builder()
                .simulacionId(simulacionId)
                .tipo("SIMULACION_REANUDADA")
                .mensaje("Simulación reanudada")
                .estado("EN_PROCESO")
                .build());
    }

    public void detenerSimulacion(String simulacionId) {
        SimulacionControl control = obtenerControl(simulacionId);

        if ("FINALIZADA".equals(control.getEstado()) || "DETENIDA".equals(control.getEstado())) {
            return;
        }

        control.detener();

        enviar("/topic/simulacion/" + simulacionId, control, EventoSimulacionDTO.builder()
                .simulacionId(simulacionId)
                .tipo("SIMULACION_DETENIDA_SOLICITADA")
                .mensaje("Solicitud de detención recibida")
                .estado("DETENIDA")
                .build());
    }

    public void cambiarVelocidad(String simulacionId, long nuevaVelocidadMs) {
        if (nuevaVelocidadMs < 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "La velocidad no puede ser negativa."
            );
        }

        SimulacionControl control = obtenerControl(simulacionId);
        control.cambiarVelocidad(nuevaVelocidadMs);

        enviar("/topic/simulacion/" + simulacionId, control, EventoSimulacionDTO.builder()
                .simulacionId(simulacionId)
                .tipo("VELOCIDAD_CAMBIADA")
                .mensaje("Velocidad cambiada a " + nuevaVelocidadMs + " ms")
                .estado(control.getEstado())
                .extra(Map.of(
                        "saMs", nuevaVelocidadMs
                ))
                .build());
    }

    public SimulacionEstadoDTO obtenerEstado(String simulacionId) {
        SimulacionControl control = obtenerControl(simulacionId);

        return new SimulacionEstadoDTO(
                control.getSimulacionId(),
                control.getEstado(),
                control.estaPausada(),
                control.estaDetenida(),
                control.getVelocidadMs(),
                control.getUltimoEventoEmitido().get(),
                control.getAlgoritmo(),
                control.getK(),
                control.getFechaInicio(),
                control.getFechaCreacion().toString()
        );
    }

    private SimulacionControl obtenerControl(String simulacionId) {
        SimulacionControl control = simulacionesActivas.get(simulacionId);

        if (control == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "No existe una simulación activa con id: " + simulacionId
            );
        }

        return control;
    }

    private void esperarConControl(SimulacionControl control, String topic) {
        long tiempoObjetivo = control.getVelocidadMs();
        long acumulado = 0L;
        long paso = 100L;

        while (acumulado < tiempoObjetivo) {
            esperarSiPausadaODetenida(control, topic);

            long dormirMs = Math.min(paso, tiempoObjetivo - acumulado);
            dormir(dormirMs);
            acumulado += dormirMs;
        }
    }

    private void esperarSiPausadaODetenida(SimulacionControl control, String topic) {
        boolean yaAvisoPausa = false;

        while (control.estaPausada()) {
            if (control.estaDetenida()) {
                throw new SimulacionDetenidaException();
            }

            if (!yaAvisoPausa) {
                enviar(topic, control, EventoSimulacionDTO.builder()
                        .simulacionId(control.getSimulacionId())
                        .tipo("SIMULACION_EN_PAUSA")
                        .mensaje("La simulación se encuentra pausada")
                        .estado("PAUSADA")
                        .build());

                yaAvisoPausa = true;
            }

            dormir(300);
        }

        if (control.estaDetenida()) {
            throw new SimulacionDetenidaException();
        }
    }

    private void enviarSimulacionDetenida(String topic, SimulacionControl control) {
        control.setEstado("DETENIDA");

        enviar(topic, control, EventoSimulacionDTO.builder()
                .simulacionId(control.getSimulacionId())
                .tipo("SIMULACION_DETENIDA")
                .mensaje("Simulación detenida correctamente")
                .estado("DETENIDA")
                .extra(Map.of(
                        "totalEventos", control.getUltimoEventoEmitido().get()
                ))
                .build());
    }

    private void cargarMaletasInicialesEnOrigenes(
            SolucionRuta solucion,
            Map<String, Integer> maletasPorAeropuerto
    ) {
        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) continue;

            String origen = asignacion.getEnvio().getOrigenIata();
            int cantidad = asignacion.getEnvio().getCantidadMaletas();

            maletasPorAeropuerto.merge(origen, cantidad, Integer::sum);
        }
    }

    private List<SimulacionVueloAgrupado> agruparVuelos(SolucionRuta solucion) {
        Map<String, SimulacionVueloAgrupado> agrupados = new LinkedHashMap<>();

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) continue;

            int cantidadMaletas = asignacion.getEnvio().getCantidadMaletas();

            for (VueloInstanciado vuelo : asignacion.getItinerario().getVuelos()) {
                String key = vuelo.getCodigoBase()
                        + "|"
                        + vuelo.getOrigenIata()
                        + "|"
                        + vuelo.getDestinoIata()
                        + "|"
                        + vuelo.getFechaHoraSalida();

                agrupados.compute(key, (k, actual) -> {
                    if (actual == null) {
                        return new SimulacionVueloAgrupado(
                                vuelo.getCodigoBase(),
                                vuelo.getOrigenIata(),
                                vuelo.getDestinoIata(),
                                vuelo.getFechaHoraSalida(),
                                vuelo.getFechaHoraLlegada(),
                                cantidadMaletas
                        );
                    }

                    actual.setCantidadMaletas(actual.getCantidadMaletas() + cantidadMaletas);
                    return actual;
                });
            }
        }

        return agrupados.values()
                .stream()
                .sorted(Comparator.comparing(SimulacionVueloAgrupado::getFechaHoraSalida))
                .toList();
    }

    private EventoSimulacionDTO crearEventoAeropuerto(
            String simulacionId,
            Aeropuerto aeropuerto,
            int maletasActuales,
            String mensaje
    ) {
        int capacidad = aeropuerto.getCapacidadAlmacen();

        double porcentaje = capacidad > 0
                ? (maletasActuales * 100.0) / capacidad
                : 0.0;

        return EventoSimulacionDTO.builder()
                .simulacionId(simulacionId)
                .tipo("AEROPUERTO_ACTUALIZADO")
                .mensaje(mensaje)
                .codigoAeropuerto(aeropuerto.getCodigoIata())
                .maletasActuales(maletasActuales)
                .capacidadAlmacen(capacidad)
                .porcentajeOcupacion(porcentaje)
                .estado(obtenerEstadoSemaforo(porcentaje))
                .build();
    }

    private String obtenerEstadoSemaforo(double porcentaje) {
        if (porcentaje >= 90.0) return "ROJO";
        if (porcentaje >= 70.0) return "AMARILLO";
        return "VERDE";
    }

    private void sumarMaletas(Map<String, Integer> mapa, String aeropuerto, int cantidad) {
        mapa.merge(aeropuerto, cantidad, Integer::sum);
    }

    private void restarMaletas(Map<String, Integer> mapa, String aeropuerto, int cantidad) {
        int actual = mapa.getOrDefault(aeropuerto, 0);
        mapa.put(aeropuerto, Math.max(0, actual - cantidad));
    }

    private void enviar(
            String topic,
            SimulacionControl control,
            EventoSimulacionDTO evento
    ) {
        evento.setNumeroEvento(control.siguienteEvento());
        evento.setFechaHoraEvento(LocalDateTime.now().toString());

        messagingTemplate.convertAndSend(topic, evento);
    }

    private void dormir(long saMs) {
        try {
            Thread.sleep(saMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class SimulacionDetenidaException extends RuntimeException {
    }
}