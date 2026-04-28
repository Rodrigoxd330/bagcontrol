package pe.edu.pucp.inf.bagcontrol.planificacion.utils;

import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Movimiento;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.RutaAsignada;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;

import java.time.Duration;
import java.util.*;

public class PlanificadorUtils {

    public static double calcularDuracionItinerarioHoras(Itinerario itinerario) {
        Duration duracion = Duration.between(
                itinerario.getFechaHoraSalida(),
                itinerario.getFechaHoraLlegada()
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

        boolean mismoContinente = origen.getContinente().equalsIgnoreCase(destino.getContinente());

        Duration tiempoTotal = Duration.between(envio.getFechaHora(), itinerario.getFechaHoraLlegada());
        double horasTotales = tiempoTotal.toMinutes() / 60.0;

        if (horasTotales < 0) return true;

        return mismoContinente ? horasTotales > 24.0 : horasTotales > 48.0;
    }

    public static List<Itinerario> buscarItinerariosViablesParaEnvio(
            Envio envio,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            Map<String, Aeropuerto> mapaAeropuertos
    ) {
        String key = envio.getOrigenIata() + "-" + envio.getDestinoIata();

        return itinerariosPorRuta.getOrDefault(key, Collections.emptyList())
                .stream()
                .filter(i -> !i.contieneVueloCancelado())
                .filter(i -> !i.getFechaHoraSalida().isBefore(envio.getFechaHora()))
                .filter(i -> !excedePlazoMaximo(envio, i, mapaAeropuertos))
                .toList();
    }

    public static List<Movimiento> generarVecindario(
            SolucionRuta solucion,
            Map<String, List<Itinerario>> itinerariosPorRuta,
            Map<String, Aeropuerto> mapaAeropuertos,
            int maxVecinos
    ) {
        List<Movimiento> movimientos = new ArrayList<>();

        List<RutaAsignada> asignaciones = new ArrayList<>(solucion.getAsignaciones());
        Collections.shuffle(asignaciones);

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
}