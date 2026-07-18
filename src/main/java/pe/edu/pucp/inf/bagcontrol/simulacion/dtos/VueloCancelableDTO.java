package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class VueloCancelableDTO {
    private Long codigoVuelo;
    private String origenIata;
    private String destinoIata;
    private String horaSalidaLocal;
    private String horaSalidaUtc;
    private String horaLlegadaLocal;
    private String horaLlegadaUtc;
    private int capacidadMax;
    private List<String> enviosAfectados;
    private int cantidadMaletas;
}
