package pe.edu.pucp.inf.bagcontrol.planificacion.utils;

import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Movimiento;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

public class PlanificadorUtils {

    public static double calcularDuracionItinerarioHoras(Itinerario itinerario) {
        Duration duracion = Duration.between(
                itinerario.getFechaHoraSalidaUtc(),
                itinerario.getFechaHoraLlegadaUtc()
        );

        return duracion.toMinutes() / 60.0;
    }

    public static boolean excedePlazoMaximo(
            Envio envio,
            Itinerario itinerario,
            Map<String, Aeropuerto> mapaAeropuertos
    ) {
        if (itinerario == null) return true;

        Aeropuerto origen = mapaAeropuertos.get(envio.getOrigenIata());
        Aeropuerto destino = mapaAeropuertos.get(envio.getDestinoIata());

        if (origen == null || destino == null) return true;

        Instant fechaEnvioUtc = obtenerFechaIngresoUtc(envio);
        Duration tiempoTotal = Duration.between(fechaEnvioUtc, itinerario.getFechaHoraLlegadaUtc());
        double horasTotales = tiempoTotal.toMinutes() / 60.0;

        if (horasTotales < 0) return true;

        return itinerario.getFechaHoraLlegadaUtc().isAfter(calcularDeadlineSla(envio, mapaAeropuertos));
    }

    public static List<Itinerario> buscarItinerariosViablesParaEnvio(
            Envio envio,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            Map<String, Aeropuerto> mapaAeropuertos
    ) {
        String key = envio.getOrigenIata() + "-" + envio.getDestinoIata();
        Aeropuerto origen = mapaAeropuertos.get(envio.getOrigenIata());

        if (origen == null) {
            return Collections.emptyList();
        }

        Instant fechaEnvioUtc = obtenerFechaIngresoUtc(envio);

        return itinerariosPorRuta.getOrDefault(key, Collections.emptyList())
                .stream()
                .filter(i -> !i.contieneVueloCancelado())
                .filter(i -> !i.getFechaHoraSalidaUtc().isBefore(fechaEnvioUtc))
                .filter(i -> !excedePlazoMaximo(envio, i, mapaAeropuertos))
                .toList();
    }

    public static Instant obtenerFechaIngresoUtc(Envio envio) {
        return envio.getFechaHora().toInstant(ZoneOffset.UTC);
    }

    public static Instant calcularDeadlineSla(Envio envio, Map<String, Aeropuerto> mapaAeropuertos) {
        Aeropuerto origen = mapaAeropuertos.get(envio.getOrigenIata());
        Aeropuerto destino = mapaAeropuertos.get(envio.getDestinoIata());
        if (origen == null || destino == null) {
            throw new IllegalArgumentException("No se pudo calcular SLA para " + envio.getIdPedido());
        }
        return obtenerFechaIngresoUtc(envio).plus(Duration.ofHours(esMismoContinente(origen, destino) ? 24 : 48));
    }

    public static String obtenerTipoSla(Envio envio, Map<String, Aeropuerto> mapaAeropuertos) {
        Aeropuerto origen = mapaAeropuertos.get(envio.getOrigenIata());
        Aeropuerto destino = mapaAeropuertos.get(envio.getDestinoIata());
        return esMismoContinente(origen, destino) ? "24H_MISMO_CONTINENTE" : "48H_INTERCONTINENTAL";
    }

    private static boolean esMismoContinente(Aeropuerto origen, Aeropuerto destino) {
        return origen != null && destino != null
                && origen.getContinente().equalsIgnoreCase(destino.getContinente());
    }

    public static List<Movimiento> generarVecindario(
            SolucionRuta solucion,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            Map<String, Aeropuerto> mapaAeropuertos,
            int maxVecinos
    ) {
        List<Movimiento> movimientos = new ArrayList<>();

        List<RutaAsignada> asignaciones = new ArrayList<>(solucion.getAsignaciones());
        asignaciones.sort(Comparator
                .comparing((RutaAsignada asignacion) -> asignacion.getItinerario() != null)
                .thenComparing(asignacion -> calcularDeadlineSla(asignacion.getEnvio(), mapaAeropuertos))
                .thenComparing(Comparator.comparingInt(
                        (RutaAsignada asignacion) -> asignacion.getEnvio().getCantidadMaletas()
                ).reversed()));

        for (RutaAsignada asignacion : asignaciones) {
            var envio = asignacion.getEnvio();
            var itinerarioActual = asignacion.getItinerario();

            List<Itinerario> alternativas = buscarItinerariosViablesParaEnvio(
                    envio,
                    itinerariosPorRuta,
                    mapaAeropuertos
            );

            for (Itinerario itinerarioNuevo : alternativas) {
                if (itinerarioActual == null ||
                        !itinerarioActual.getIdItinerario().equals(itinerarioNuevo.getIdItinerario())) {

                    movimientos.add(new Movimiento(envio, itinerarioActual, itinerarioNuevo));

                    if (movimientos.size() >= maxVecinos) {
                        return movimientos;
                    }
                }
            }
        }

        return movimientos;
    }

    public static boolean itinerarioTieneCapacidad(
            Itinerario itinerario,
            Envio envio,
            Map<VueloInstanciado, Integer> cargaAcumulada
    ) {
        for (VueloInstanciado vuelo : itinerario.getVuelos()) {
            if (vuelo.isEstaCancelado()) {
                return false;
            }
            int cargaActual = cargaAcumulada.getOrDefault(vuelo, 0);
            if (cargaActual + envio.getCantidadMaletas() > vuelo.getCapacidadMax()) {
                return false;
            }
        }

        return true;
    }

    public static void acumularCargaItinerario(
            Itinerario itinerario,
            Envio envio,
            Map<VueloInstanciado, Integer> cargaAcumulada
    ) {
        for (VueloInstanciado vuelo : itinerario.getVuelos()) {
            cargaAcumulada.merge(vuelo, envio.getCantidadMaletas(), Integer::sum);
        }
    }

    public static boolean solucionRespetaCapacidadAeropuertos(
            SolucionRuta solucion,
            Map<String, Aeropuerto> mapaAeropuertos,
            Map<String, Integer> inventarioInicial
    ) {
        return solucionRespetaCapacidadAeropuertos(
                solucion, mapaAeropuertos, inventarioInicial, Collections.emptySet()
        );
    }

    public static boolean solucionRespetaCapacidadAeropuertos(
            SolucionRuta solucion,
            Map<String, Aeropuerto> mapaAeropuertos,
            Map<String, Integer> inventarioInicial,
            Set<String> enviosNuevos
    ) {
        Map<String, NavigableMap<Instant, Integer>> movimientosPorAeropuerto = new HashMap<>();

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) {
                continue;
            }
            if (enviosNuevos.contains(asignacion.getEnvio().getIdPedido())) {
                registrarMovimiento(
                        movimientosPorAeropuerto,
                        asignacion.getEnvio().getOrigenIata(),
                        obtenerFechaIngresoUtc(asignacion.getEnvio()),
                        asignacion.getEnvio().getCantidadMaletas()
                );
            }
            registrarMovimientosAeropuertos(
                    asignacion.getItinerario(),
                    asignacion.getEnvio().getCantidadMaletas(),
                    movimientosPorAeropuerto
            );
        }

        Set<String> aeropuertosEvaluados = new HashSet<>(inventarioInicial.keySet());
        aeropuertosEvaluados.addAll(movimientosPorAeropuerto.keySet());
        for (String codigoIata : aeropuertosEvaluados) {
            Aeropuerto aeropuerto = mapaAeropuertos.get(codigoIata);
            if (aeropuerto == null) {
                return false;
            }

            int ocupacion = inventarioInicial.getOrDefault(codigoIata, 0);
            if (ocupacion < 0 || ocupacion > aeropuerto.getCapacidadAlmacen()) {
                return false;
            }

            for (int variacion : movimientosPorAeropuerto
                    .getOrDefault(codigoIata, Collections.emptyNavigableMap())
                    .values()) {
                ocupacion += variacion;
                if (ocupacion < 0 || ocupacion > aeropuerto.getCapacidadAlmacen()) {
                    return false;
                }
            }
        }
        return true;
    }

    public static Map<String, Integer> construirInventarioInicial(
            List<Envio> envios,
            Map<String, Integer> inventarioActual
    ) {
        Map<String, Integer> inventarioInicial = new HashMap<>();
        if (inventarioActual != null) {
            inventarioInicial.putAll(inventarioActual);
        }
        for (Envio envio : envios) {
            inventarioInicial.merge(envio.getOrigenIata(), envio.getCantidadMaletas(), Integer::sum);
        }
        return inventarioInicial;
    }

    public static void reservarEscalasSolucion(SolucionRuta solucion, Map<String, Integer> inventarioReservado) {
        Map<String, Integer> variacionAcumulada = new HashMap<>();
        Map<String, Integer> reservaPico = new HashMap<>();
        List<MovimientoInventario> movimientos = new ArrayList<>();

        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) {
                continue;
            }
            List<VueloInstanciado> vuelos = asignacion.getItinerario().getVuelos();
            int cantidad = asignacion.getEnvio().getCantidadMaletas();
            for (int i = 0; i < vuelos.size() - 1; i++) {
                movimientos.add(new MovimientoInventario(
                        vuelos.get(i).getFechaHoraLlegadaUtc(), vuelos.get(i).getDestinoIata(), cantidad
                ));
                movimientos.add(new MovimientoInventario(
                        vuelos.get(i + 1).getFechaHoraSalidaUtc(), vuelos.get(i + 1).getOrigenIata(), -cantidad
                ));
            }
        }

        acumularReservaPico(movimientos, variacionAcumulada, reservaPico);
        reservaPico.forEach((codigoIata, reserva) -> inventarioReservado.merge(codigoIata, reserva, Integer::sum));
    }

    public static Map<String, Integer> construirInventarioReservado(
            Map<String, RutaAsignada> enviosEnSeguimiento,
            Set<String> enviosEntregados,
            Map<String, Integer> inventarioSnapshot,
            Instant referencia
    ) {
        Map<String, Integer> variacionAcumulada = new HashMap<>();
        Map<String, Integer> reservaPico = new HashMap<>();
        List<MovimientoInventario> movimientos = new ArrayList<>();

        for (RutaAsignada asignacion : enviosEnSeguimiento.values()) {
            if (asignacion.getItinerario() == null
                    || enviosEntregados.contains(asignacion.getEnvio().getIdPedido())) {
                continue;
            }
            List<VueloInstanciado> vuelos = asignacion.getItinerario().getVuelos();
            int cantidad = asignacion.getEnvio().getCantidadMaletas();
            for (int i = 0; i < vuelos.size(); i++) {
                VueloInstanciado vuelo = vuelos.get(i);
                if (!vuelo.getFechaHoraSalidaUtc().isBefore(referencia)) {
                    movimientos.add(new MovimientoInventario(
                            vuelo.getFechaHoraSalidaUtc(), vuelo.getOrigenIata(), -cantidad
                    ));
                }
                if (i < vuelos.size() - 1 && !vuelo.getFechaHoraLlegadaUtc().isBefore(referencia)) {
                    movimientos.add(new MovimientoInventario(
                            vuelo.getFechaHoraLlegadaUtc(), vuelo.getDestinoIata(), cantidad
                    ));
                }
            }
        }

        acumularReservaPico(movimientos, variacionAcumulada, reservaPico);

        Map<String, Integer> inventarioReservado = new HashMap<>(inventarioSnapshot);
        reservaPico.forEach((codigoIata, reserva) -> inventarioReservado.merge(codigoIata, reserva, Integer::sum));
        return inventarioReservado;
    }

    private static void registrarMovimientosAeropuertos(
            Itinerario itinerario,
            int cantidadMaletas,
            Map<String, NavigableMap<Instant, Integer>> movimientosPorAeropuerto
    ) {
        for (VueloInstanciado vuelo : itinerario.getVuelos()) {
            registrarMovimiento(
                    movimientosPorAeropuerto, vuelo.getOrigenIata(), vuelo.getFechaHoraSalidaUtc(), -cantidadMaletas
            );
            registrarMovimiento(
                    movimientosPorAeropuerto, vuelo.getDestinoIata(), vuelo.getFechaHoraLlegadaUtc(), cantidadMaletas
            );
        }
    }

    private static void registrarMovimiento(
            Map<String, NavigableMap<Instant, Integer>> movimientosPorAeropuerto,
            String codigoIata,
            Instant instante,
            int variacion
    ) {
        movimientosPorAeropuerto
                .computeIfAbsent(codigoIata, key -> new TreeMap<>())
                .merge(instante, variacion, Integer::sum);
    }

    private static void acumularReservaPico(
            List<MovimientoInventario> movimientos,
            Map<String, Integer> variacionAcumulada,
            Map<String, Integer> reservaPico
    ) {
        movimientos.stream()
                .sorted(Comparator.comparing(MovimientoInventario::instante))
                .forEach(movimiento -> {
                    int acumulado = variacionAcumulada.merge(
                            movimiento.codigoIata(), movimiento.variacion(), Integer::sum
                    );
                    reservaPico.merge(movimiento.codigoIata(), Math.max(acumulado, 0), Math::max);
                });
    }

    private record MovimientoInventario(Instant instante, String codigoIata, int variacion) {
    }
}
