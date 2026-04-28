package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import org.springframework.stereotype.Service;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Itinerario;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.time.Duration;
import java.util.*;

@Service
public class ItinerarioService {

    private static final int MIN_CONEXION_MINUTOS = 30;
    private static final int MAX_ESPERA_ESCALA_HORAS = 12;
    private static final int MAX_ITINERARIOS_POR_RUTA = 300;

    public Map<String, List<Itinerario>> generarItinerariosPorRuta(List<VueloInstanciado> vuelos) {
        Map<String, List<VueloInstanciado>> vuelosPorOrigen = new HashMap<>();
        Map<String, List<Itinerario>> itinerariosPorRuta = new HashMap<>();

        for (VueloInstanciado vuelo : vuelos) {
            if (vuelo.isEstaCancelado()) continue;

            vuelosPorOrigen
                    .computeIfAbsent(vuelo.getOrigenIata(), k -> new ArrayList<>())
                    .add(vuelo);

            String keyDirecto = vuelo.getOrigenIata() + "-" + vuelo.getDestinoIata();
            itinerariosPorRuta
                    .computeIfAbsent(keyDirecto, k -> new ArrayList<>())
                    .add(new Itinerario(List.of(vuelo)));
        }

        for (VueloInstanciado primerVuelo : vuelos) {
            if (primerVuelo.isEstaCancelado()) continue;

            List<VueloInstanciado> segundosVuelos =
                    vuelosPorOrigen.getOrDefault(primerVuelo.getDestinoIata(), Collections.emptyList());

            for (VueloInstanciado segundoVuelo : segundosVuelos) {
                if (segundoVuelo.isEstaCancelado()) continue;

                if (primerVuelo.getOrigenIata().equalsIgnoreCase(segundoVuelo.getDestinoIata())) {
                    continue;
                }

                if (!conexionValida(primerVuelo, segundoVuelo)) {
                    continue;
                }

                String keyConEscala = primerVuelo.getOrigenIata() + "-" + segundoVuelo.getDestinoIata();

                itinerariosPorRuta
                        .computeIfAbsent(keyConEscala, k -> new ArrayList<>())
                        .add(new Itinerario(List.of(primerVuelo, segundoVuelo)));
            }
        }

        for (List<Itinerario> lista : itinerariosPorRuta.values()) {
            lista.sort(Comparator.comparingDouble(PlanificadorUtils::calcularDuracionItinerarioHoras));

            if (lista.size() > MAX_ITINERARIOS_POR_RUTA) {
                lista.subList(MAX_ITINERARIOS_POR_RUTA, lista.size()).clear();
            }
        }

        return itinerariosPorRuta;
    }

    private boolean conexionValida(VueloInstanciado primero, VueloInstanciado segundo) {
        var llegadaPrimero = primero.getFechaHoraLlegada();
        var salidaSegundo = segundo.getFechaHoraSalida();

        if (salidaSegundo.isBefore(llegadaPrimero.plusMinutes(MIN_CONEXION_MINUTOS))) {
            return false;
        }

        long esperaHoras = Duration.between(llegadaPrimero, salidaSegundo).toHours();

        return esperaHoras <= MAX_ESPERA_ESCALA_HORAS;
    }
}