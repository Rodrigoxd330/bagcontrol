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

            // Si la llegada es antes que la salida, el vuelo cruza medianoche.
            // Ejemplo: sale 23:00 y llega 02:00 del día siguiente.
            if (vuelo.getHoraLlegada().isBefore(vuelo.getHoraSalida())) {
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