package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EscalaRutaDTO {
    private Long codigoVuelo;
    private String origenIata;
    private String destinoIata;
    private String horaSalidaUtc;
    private String horaLlegadaUtc;
    private String horaSalidaLocal;
    private String horaLlegadaLocal;
    private int capacidadMax;
    private boolean cancelado;
    private String motivoCancelacion;
}
