package pe.edu.pucp.inf.bagcontrol.entidades.vuelo;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class VueloFactory {

    public List<VueloInstanciado> crearInstanciasDelDia(List<Vuelo> vuelos, LocalDate fecha) {
        List<VueloInstanciado> instancias = new ArrayList<>();

        for (Vuelo vuelo : vuelos) {
            LocalDateTime salida = LocalDateTime.of(fecha, vuelo.getHoraSalida());
            LocalDateTime llegada = LocalDateTime.of(fecha, vuelo.getHoraLlegada());

            // Si llega “antes” que la salida, asumimos que cruza medianoche
            if (llegada.isBefore(salida)) {
                llegada = llegada.plusDays(1);
            }

            VueloInstanciado instancia = new VueloInstanciado(
                    vuelo,
                    salida,
                    llegada,
                    0
            );

            instancias.add(instancia);
        }

        return instancias;
    }
}