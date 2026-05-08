package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VueloInstanciadoDTO {
    private Long codigoBase;
    private String origenIata;
    private String destinoIata;
    private String fechaHoraSalida;
    private String fechaHoraLlegada;
    private int capacidadMax;
    private int ocupacionActual;
    private boolean estaCancelado;
}