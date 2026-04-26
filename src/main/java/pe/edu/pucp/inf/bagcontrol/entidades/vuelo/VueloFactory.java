package pe.edu.pucp.inf.bagcontrol.entidades.vuelo;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class VueloFactory {

    public List<VueloInstanciado> crearInstanciasDelDia(List<Vuelo> vuelos, LocalDate fecha) {
        // Inicializamos la lista que contendrá las instancias de vuelos
        List<VueloInstanciado> instancias = new ArrayList<>();

        // Iteramos sobre cada vuelo de la lista de vuelos
        for (Vuelo vuelo : vuelos) {
            // Creamos la hora de salida y de llegada para el vuelo utilizando la fecha y la hora
            LocalDateTime salida = LocalDateTime.of(fecha, vuelo.getHoraSalida());
            LocalDateTime llegada = LocalDateTime.of(fecha, vuelo.getHoraLlegada());

            // Si la hora de llegada es antes que la de salida, asumimos que cruza medianoche, por lo que le sumamos un día
            if (llegada.isBefore(salida)) {
                llegada = llegada.plusDays(1);
            }

            // Creamos una nueva instancia del vuelo para ese día y con los horarios correspondientes
            VueloInstanciado instancia = new VueloInstanciado(
                    vuelo,        // Vuelo original
                    salida,       // Hora de salida
                    llegada,      // Hora de llegada
                    0             // Capacidad inicial (puedes modificar si es necesario)
            );

            // Añadimos la instancia de vuelo a la lista
            instancias.add(instancia);
        }

        // Retornamos la lista de vuelos instanciados
        return instancias;
    }
}