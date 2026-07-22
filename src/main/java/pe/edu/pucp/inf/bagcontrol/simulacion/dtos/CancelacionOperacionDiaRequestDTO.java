package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.Data;

@Data
public class CancelacionOperacionDiaRequestDTO {
    private Long codigoVuelo;
    private String salidaUtc;
}
