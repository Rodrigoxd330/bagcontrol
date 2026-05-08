package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SimulacionVueloAgrupado {
    private Long codigoVuelo;
    private String origenIata;
    private String destinoIata;
    private LocalDateTime fechaHoraSalida;
    private LocalDateTime fechaHoraLlegada;
    private Instant fechaHoraSalidaUtc;
    private Instant fechaHoraLlegadaUtc;
    private int cantidadMaletas;
}
