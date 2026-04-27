package pe.edu.pucp.inf.bagcontrol.planificacion.utils;

import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Movimiento;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;

import java.time.Duration;
import java.util.*;

public class PlanificadorUtils {

    public static List<VueloInstanciado> buscarVuelosPosiblesParaEnvio(Envio envio, List<VueloInstanciado> vuelos) {
        List<VueloInstanciado> posibles = new ArrayList<>();
        for (VueloInstanciado vuelo : vuelos) {
            if (vuelo.isEstaCancelado()) continue;

            boolean coincideOrigen = vuelo.getOrigenIata().equalsIgnoreCase(envio.getOrigenIata());
            boolean coincideDestino = vuelo.getDestinoIata().equalsIgnoreCase(envio.getDestinoIata());

            if (coincideOrigen && coincideDestino) {
                posibles.add(vuelo);
            }
        }
        return posibles;
    }

    public static double calcularDuracionVueloHoras(VueloInstanciado vuelo) {
        Duration duracion = Duration.between(vuelo.getFechaHoraSalida(), vuelo.getFechaHoraLlegada());
        return duracion.toMinutes() / 60.0;
    }

    public static boolean excedePlazoMaximo(
            Envio envio,
            VueloInstanciado vuelo,
            Map<String, Aeropuerto> mapaAeropuertos
    ) {
        Aeropuerto origen = mapaAeropuertos.get(envio.getOrigenIata());
        Aeropuerto destino = mapaAeropuertos.get(envio.getDestinoIata());

        if (origen == null || destino == null) return true;

        boolean mismoContinente =
                origen.getContinente().equalsIgnoreCase(destino.getContinente());

        Duration tiempoTotal =
                Duration.between(envio.getFechaHora(), vuelo.getFechaHoraLlegada());

        double horasTotales = tiempoTotal.toMinutes() / 60.0;

        if (horasTotales < 0) return true;

        return mismoContinente
                ? horasTotales > 24.0
                : horasTotales > 48.0;
    }

    public static List<Movimiento> generarVecindario(
            SolucionRuta solucion,
            Map<String, List<VueloInstanciado>> vuelosPorRuta,
            int maxVecinos
    ) {
        List<Movimiento> movimientos = new ArrayList<>();

        List<RutaAsignada> asignaciones = new ArrayList<>(solucion.getAsignaciones());
        Collections.shuffle(asignaciones);

        for (RutaAsignada asignacion : asignaciones) {
            var envio = asignacion.getEnvio();
            var vueloActual = asignacion.getVuelo();
            String key = envio.getOrigenIata() + "-" + envio.getDestinoIata();
            List<VueloInstanciado> alternativas =
                    vuelosPorRuta.getOrDefault(key, Collections.emptyList());
            for (VueloInstanciado vueloNuevo : alternativas) {
                if (vueloActual == null ||
                        !esMismoVueloInstanciado(vueloActual, vueloNuevo)) {

                    movimientos.add(new Movimiento(envio, vueloActual, vueloNuevo));

                    // 🔥 corte temprano = clave para performance
                    if (movimientos.size() >= maxVecinos) {
                        return movimientos;
                    }
                }
            }
        }
        return movimientos;
    }

    private static boolean esMismoVueloInstanciado(VueloInstanciado a, VueloInstanciado b) {
        if (a == null || b == null) return false;

        return a.getCodigoBase().equals(b.getCodigoBase())
                && a.getFechaHoraSalida().equals(b.getFechaHoraSalida());
    }

    public static Map<String, List<VueloInstanciado>> indexarVuelosPorRuta(
            List<VueloInstanciado> vuelos) {
        Map<String, List<VueloInstanciado>> mapa = new HashMap<>();
        for (VueloInstanciado vuelo : vuelos) {
            String key = vuelo.getOrigenIata() + "-" + vuelo.getDestinoIata();
            mapa.computeIfAbsent(key, k -> new ArrayList<>()).add(vuelo);
        }
        for (List<VueloInstanciado> lista : mapa.values()) {
            lista.sort((v1, v2) -> {
                double d1 = calcularDuracionVueloHoras(v1);
                double d2 = calcularDuracionVueloHoras(v2);
                return Double.compare(d1, d2);
            });
        }
        return mapa;
    }


}