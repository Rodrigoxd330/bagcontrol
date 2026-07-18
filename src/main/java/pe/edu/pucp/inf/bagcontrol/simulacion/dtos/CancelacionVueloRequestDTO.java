package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.Data;

@Data
public class CancelacionVueloRequestDTO {
    private String instanteSimulado;
    private String motivo;
}
