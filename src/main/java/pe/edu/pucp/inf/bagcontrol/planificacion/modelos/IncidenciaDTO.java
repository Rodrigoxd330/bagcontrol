package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IncidenciaDTO {
    private Long id;
    private LocalDateTime fechaHora;
    private String descripcion;
    private String origenIata;
    private boolean noPuedeRecibir;
    private boolean noPuedeEnviar;
    private int tiempoRecuperacionMinutos;
}
