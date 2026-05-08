package pe.edu.pucp.inf.bagcontrol.entidades.vuelo;

import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.ZonaHorariaUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class VueloFactory {

    public List<VueloInstanciado> crearInstanciasDelDia(
            List<Vuelo> vuelos,
            LocalDate fecha,
            List<Aeropuerto> aeropuertos
    ) {
        Map<String, Aeropuerto> mapaAeropuertos = aeropuertos.stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIata, a -> a));
        return crearInstanciasDelDia(vuelos, fecha, mapaAeropuertos);
    }

    public List<VueloInstanciado> crearInstanciasDelDia(
            List<Vuelo> vuelos,
            LocalDate fecha,
            Map<String, Aeropuerto> mapaAeropuertos
    ) {
        List<VueloInstanciado> instancias = new ArrayList<>();

        for (Vuelo vuelo : vuelos) {
            Aeropuerto origen = mapaAeropuertos.get(vuelo.getOrigenIata());
            Aeropuerto destino = mapaAeropuertos.get(vuelo.getDestinoIata());

            if (origen == null || destino == null) {
                throw new IllegalStateException("No se encontro aeropuerto para el vuelo "
                        + vuelo.getOrigenIata() + " -> " + vuelo.getDestinoIata());
            }

            LocalDateTime salida = LocalDateTime.of(fecha, vuelo.getHoraSalida());
            LocalDateTime llegada = LocalDateTime.of(fecha, vuelo.getHoraLlegada());
            Instant salidaUtc = ZonaHorariaUtils.convertirLocalAInstant(salida, origen);
            Instant llegadaUtc = ZonaHorariaUtils.convertirLocalAInstant(llegada, destino);

            while (!llegadaUtc.isAfter(salidaUtc)) {
                llegada = llegada.plusDays(1);
                llegadaUtc = ZonaHorariaUtils.convertirLocalAInstant(llegada, destino);
            }

            instancias.add(new VueloInstanciado(
                    vuelo,
                    salida,
                    llegada,
                    salidaUtc,
                    llegadaUtc,
                    0
            ));
        }

        return instancias;
    }
}
