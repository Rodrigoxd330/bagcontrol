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

    private static final ThreadLocal<MetricasRendimiento> METRICAS =
            ThreadLocal.withInitial(MetricasRendimiento::new);

    public static void reiniciarMetricasRendimiento() {
        METRICAS.set(new MetricasRendimiento());
    }

    public static MetricasRendimiento snapshotMetricasRendimiento() {
        return METRICAS.get().copiar();
    }

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
        return generarVecindario(solucion, itinerariosPorRuta, mapaAeropuertos, Map.of(), maxVecinos);
    }

    public static List<Movimiento> generarVecindario(
            SolucionRuta solucion,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            Map<String, Aeropuerto> mapaAeropuertos,
            Map<String, Integer> inventarioInicial,
            int maxVecinos
    ) {
        if (maxVecinos <= 0) {
            return List.of();
        }

        Map<String, Double> presionAeropuertos = calcularPresionAeropuertos(
                solucion, mapaAeropuertos, inventarioInicial
        );

        List<RutaAsignada> asignaciones = new ArrayList<>(solucion.getAsignaciones());
        asignaciones.sort(Comparator
                .comparing((RutaAsignada asignacion) -> asignacion.getItinerario() != null)
                .thenComparing(Comparator.comparingDouble(
                        (RutaAsignada asignacion) -> presionRuta(asignacion.getItinerario(), presionAeropuertos)
                ).reversed())
                .thenComparing(asignacion -> calcularDeadlineSla(asignacion.getEnvio(), mapaAeropuertos))
                .thenComparing(Comparator.comparingInt(
                        (RutaAsignada asignacion) -> asignacion.getEnvio().getCantidadMaletas()
                ).reversed()));

        int maxEnviosEvaluados = Math.min(asignaciones.size(), Math.max(20, maxVecinos * 2));
        int cuotaPorEnvio = 3;
        List<CandidatoMovimiento> candidatos = new ArrayList<>();
        for (RutaAsignada asignacion : asignaciones.subList(0, maxEnviosEvaluados)) {
            var envio = asignacion.getEnvio();
            var itinerarioActual = asignacion.getItinerario();

            List<Itinerario> alternativas = buscarItinerariosViablesParaEnvio(
                    envio,
                    itinerariosPorRuta,
                    mapaAeropuertos
            ).stream()
                    .filter(itinerarioNuevo -> itinerarioActual == null
                            || !itinerarioActual.getIdItinerario().equals(itinerarioNuevo.getIdItinerario()))
                    .sorted(Comparator
                            .comparingDouble((Itinerario itinerario) -> presionRuta(itinerario, presionAeropuertos))
                            .thenComparing(Itinerario::getFechaHoraLlegadaUtc)
                            .thenComparingInt(Itinerario::getCantidadVuelos))
                    .limit(cuotaPorEnvio)
                    .toList();

            for (Itinerario itinerarioNuevo : alternativas) {
                Movimiento movimiento = new Movimiento(envio, itinerarioActual, itinerarioNuevo);
                double alivio = presionRuta(itinerarioActual, presionAeropuertos)
                        - presionRuta(itinerarioNuevo, presionAeropuertos);
                candidatos.add(new CandidatoMovimiento(
                        movimiento, itinerarioActual == null, alivio,
                        presionRuta(itinerarioNuevo, presionAeropuertos)
                ));
            }
        }

        candidatos.sort(Comparator
                .comparing(CandidatoMovimiento::pendiente).reversed()
                .thenComparing(Comparator.comparingDouble(CandidatoMovimiento::alivio).reversed())
                .thenComparingDouble(CandidatoMovimiento::presionNueva)
                .thenComparing(candidato -> candidato.movimiento().getItinerarioNuevo().getFechaHoraLlegadaUtc()));

        int dirigidos = Math.min(candidatos.size(), (int) Math.ceil(maxVecinos * 0.8));
        List<Movimiento> movimientos = new ArrayList<>(maxVecinos);
        Set<String> usados = new HashSet<>();
        for (int i = 0; i < dirigidos; i++) {
            Movimiento movimiento = candidatos.get(i).movimiento();
            movimientos.add(movimiento);
            usados.add(movimiento.getIdMovimientoTabu());
        }

        candidatos.stream()
                .map(CandidatoMovimiento::movimiento)
                .filter(movimiento -> !usados.contains(movimiento.getIdMovimientoTabu()))
                .sorted(Comparator.comparingInt(movimiento -> movimiento.getIdMovimientoTabu().hashCode()))
                .limit(maxVecinos - movimientos.size())
                .forEach(movimientos::add);
        return movimientos;
    }

    private static Map<String, Double> calcularPresionAeropuertos(
            SolucionRuta solucion,
            Map<String, Aeropuerto> aeropuertos,
            Map<String, Integer> inventarioInicial
    ) {
        Map<String, Integer> cargaEscalas = new HashMap<>(inventarioInicial);
        for (RutaAsignada asignacion : solucion.getAsignaciones()) {
            if (asignacion.getItinerario() == null) {
                continue;
            }
            List<VueloInstanciado> vuelos = asignacion.getItinerario().getVuelos();
            for (int i = 0; i < vuelos.size() - 1; i++) {
                cargaEscalas.merge(
                        vuelos.get(i).getDestinoIata(), asignacion.getEnvio().getCantidadMaletas(), Integer::sum
                );
            }
        }

        Map<String, Double> presion = new HashMap<>();
        cargaEscalas.forEach((iata, carga) -> {
            Aeropuerto aeropuerto = aeropuertos.get(iata);
            if (aeropuerto != null && aeropuerto.getCapacidadAlmacen() > 0) {
                presion.put(iata, carga / (double) aeropuerto.getCapacidadAlmacen());
            }
        });
        return presion;
    }

    private static double presionRuta(Itinerario itinerario, Map<String, Double> presionAeropuertos) {
        if (itinerario == null) {
            return 0.0;
        }
        List<VueloInstanciado> vuelos = itinerario.getVuelos();
        double presion = 0.0;
        for (int i = 0; i < vuelos.size() - 1; i++) {
            double ocupacion = presionAeropuertos.getOrDefault(vuelos.get(i).getDestinoIata(), 0.0);
            presion += ocupacion * ocupacion;
        }
        return presion;
    }

    private record CandidatoMovimiento(
            Movimiento movimiento,
            boolean pendiente,
            double alivio,
            double presionNueva
    ) {
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
            RutaAsignada asignacion,
            Map<String, Aeropuerto> mapaAeropuertos,
            Map<String, Integer> inventarioInicial
    ) {
        return solucionRespetaCapacidadAeropuertos(
                asignacion, mapaAeropuertos, inventarioInicial, Collections.emptySet()
        );
    }

    public static boolean solucionRespetaCapacidadAeropuertos(
            SolucionRuta solucion,
            Map<String, Aeropuerto> mapaAeropuertos,
            Map<String, Integer> inventarioInicial,
            Set<String> enviosNuevos
    ) {
        long inicioNanos = System.nanoTime();
        MetricasRendimiento metricas = METRICAS.get();
        metricas.llamadasValidacionCapacidad++;
        try {
            return solucionRespetaCapacidadAeropuertosInterna(
                    solucion, mapaAeropuertos, inventarioInicial, enviosNuevos
            );
        } finally {
            metricas.tiempoValidacionCapacidadNanos += System.nanoTime() - inicioNanos;
        }
    }

    public static boolean solucionRespetaCapacidadAeropuertos(
            RutaAsignada asignacion,
            Map<String, Aeropuerto> mapaAeropuertos,
            Map<String, Integer> inventarioInicial,
            Set<String> enviosNuevos
    ) {
        SolucionRuta solucion = new SolucionRuta();
        solucion.agregarAsignacion(asignacion.getEnvio(), asignacion.getItinerario());
        return solucionRespetaCapacidadAeropuertos(
                solucion, mapaAeropuertos, inventarioInicial, enviosNuevos
        );
    }

    private static boolean solucionRespetaCapacidadAeropuertosInterna(
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

    public static EvaluadorCapacidadIncremental crearEvaluadorCapacidadIncremental(
            Map<String, Aeropuerto> mapaAeropuertos,
            Map<String, Integer> inventarioInicial,
            Set<String> enviosNuevos
    ) {
        return new EvaluadorCapacidadIncremental(mapaAeropuertos, inventarioInicial, enviosNuevos);
    }

    public static final class EvaluadorCapacidadIncremental {
        private final Map<String, Aeropuerto> aeropuertos;
        private final Map<String, Integer> inventarioInicial;
        private final Set<String> enviosNuevos;
        private final Map<String, NavigableMap<Instant, Integer>> movimientos = new HashMap<>();

        private EvaluadorCapacidadIncremental(
                Map<String, Aeropuerto> aeropuertos,
                Map<String, Integer> inventarioInicial,
                Set<String> enviosNuevos
        ) {
            this.aeropuertos = aeropuertos;
            this.inventarioInicial = inventarioInicial;
            this.enviosNuevos = enviosNuevos;
        }

        public boolean respetaCapacidadAlAgregar(Envio envio, Itinerario itinerario) {
            long inicioNanos = System.nanoTime();
            MetricasRendimiento metricas = METRICAS.get();
            metricas.llamadasValidacionCapacidad++;
            Map<String, NavigableMap<Instant, Integer>> candidato = new HashMap<>();
            try {
                if (enviosNuevos.contains(envio.getIdPedido())) {
                    registrarMovimiento(candidato, envio.getOrigenIata(), obtenerFechaIngresoUtc(envio),
                            envio.getCantidadMaletas());
                }
                registrarMovimientosAeropuertos(itinerario, envio.getCantidadMaletas(), candidato);

                for (Map.Entry<String, NavigableMap<Instant, Integer>> entry : candidato.entrySet()) {
                    String codigoIata = entry.getKey();
                    Aeropuerto aeropuerto = aeropuertos.get(codigoIata);
                    if (aeropuerto == null) return false;

                    NavigableMap<Instant, Integer> combinados = new TreeMap<>(
                            movimientos.getOrDefault(codigoIata, Collections.emptyNavigableMap())
                    );
                    entry.getValue().forEach((instante, variacion) ->
                            combinados.merge(instante, variacion, Integer::sum));

                    int ocupacion = inventarioInicial.getOrDefault(codigoIata, 0);
                    if (ocupacion < 0 || ocupacion > aeropuerto.getCapacidadAlmacen()) return false;
                    for (int variacion : combinados.values()) {
                        ocupacion += variacion;
                        if (ocupacion < 0 || ocupacion > aeropuerto.getCapacidadAlmacen()) return false;
                    }
                }
                return true;
            } finally {
                metricas.tiempoValidacionCapacidadNanos += System.nanoTime() - inicioNanos;
            }
        }

        public void agregar(Envio envio, Itinerario itinerario) {
            if (enviosNuevos.contains(envio.getIdPedido())) {
                registrarMovimiento(movimientos, envio.getOrigenIata(), obtenerFechaIngresoUtc(envio),
                        envio.getCantidadMaletas());
            }
            registrarMovimientosAeropuertos(itinerario, envio.getCantidadMaletas(), movimientos);
        }
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
        inventarioReservado.replaceAll((codigoIata, reservado) ->
                Math.min(reservado, inventarioSnapshot.getOrDefault(codigoIata, 0)));
        return inventarioReservado;
    }

    public static Map<String, Integer> construirInventarioProyectado(
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
        Map<String, Integer> inventarioProyectado = new HashMap<>(inventarioSnapshot);
        reservaPico.forEach((codigoIata, reserva) -> inventarioProyectado.merge(codigoIata, reserva, Integer::sum));
        return inventarioProyectado;
    }

    private static void registrarMovimientosAeropuertos(
            Itinerario itinerario,
            int cantidadMaletas,
            Map<String, NavigableMap<Instant, Integer>> movimientosPorAeropuerto
    ) {
        long inicioNanos = System.nanoTime();
        MetricasRendimiento metricas = METRICAS.get();
        metricas.llamadasRegistrarMovimientosAeropuertos++;
        try {
            for (VueloInstanciado vuelo : itinerario.getVuelos()) {
                registrarMovimiento(
                        movimientosPorAeropuerto, vuelo.getOrigenIata(), vuelo.getFechaHoraSalidaUtc(), -cantidadMaletas
                );
                registrarMovimiento(
                        movimientosPorAeropuerto, vuelo.getDestinoIata(), vuelo.getFechaHoraLlegadaUtc(), cantidadMaletas
                );
                metricas.movimientosAeropuertoGenerados += 2;
            }
        } finally {
            metricas.tiempoRegistrarMovimientosAeropuertosNanos += System.nanoTime() - inicioNanos;
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

    public static final class MetricasRendimiento {
        private long llamadasRegistrarMovimientosAeropuertos;
        private long movimientosAeropuertoGenerados;
        private long tiempoRegistrarMovimientosAeropuertosNanos;
        private long llamadasValidacionCapacidad;
        private long tiempoValidacionCapacidadNanos;

        private MetricasRendimiento copiar() {
            MetricasRendimiento copia = new MetricasRendimiento();
            copia.llamadasRegistrarMovimientosAeropuertos = llamadasRegistrarMovimientosAeropuertos;
            copia.movimientosAeropuertoGenerados = movimientosAeropuertoGenerados;
            copia.tiempoRegistrarMovimientosAeropuertosNanos = tiempoRegistrarMovimientosAeropuertosNanos;
            copia.llamadasValidacionCapacidad = llamadasValidacionCapacidad;
            copia.tiempoValidacionCapacidadNanos = tiempoValidacionCapacidadNanos;
            return copia;
        }

        public long llamadasRegistrarMovimientosAeropuertos() { return llamadasRegistrarMovimientosAeropuertos; }
        public long movimientosAeropuertoGenerados() { return movimientosAeropuertoGenerados; }
        public long tiempoRegistrarMovimientosAeropuertosMs() { return tiempoRegistrarMovimientosAeropuertosNanos / 1_000_000; }
        public long llamadasValidacionCapacidad() { return llamadasValidacionCapacidad; }
        public long tiempoValidacionCapacidadMs() { return tiempoValidacionCapacidadNanos / 1_000_000; }
    }
}
