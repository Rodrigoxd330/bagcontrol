package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VueloDTO {
    private Long codigo;
    private String origenIata;
    private String destinoIata;
    private LocalTime horaSalida;
    private LocalTime horaLlegada;
    private int capacidadMax;
    private boolean estaCancelado;
}
