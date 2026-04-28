package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Itinerario {

    private List<VueloInstanciado> vuelos;

    public String getOrigenIata() {
        return vuelos.get(0).getOrigenIata();
    }

    public String getDestinoIata() {
        return vuelos.get(vuelos.size() - 1).getDestinoIata();
    }

    public LocalDateTime getFechaHoraSalida() {
        return vuelos.get(0).getFechaHoraSalida();
    }

    public LocalDateTime getFechaHoraLlegada() {
        return vuelos.get(vuelos.size() - 1).getFechaHoraLlegada();
    }

    public int getCantidadVuelos() {
        return vuelos.size();
    }

    public String getIdItinerario() {
        return vuelos.stream()
                .map(v -> v.getCodigoBase() + "@" + v.getFechaHoraSalida())
                .collect(Collectors.joining("|"));
    }

    public boolean contieneVueloCancelado() {
        return vuelos.stream().anyMatch(VueloInstanciado::isEstaCancelado);
    }
}