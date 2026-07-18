package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class CancelacionVueloResponseDTO {
    private Long codigoVuelo;
    private String origenIata;
    private String destinoIata;
    private String registradaEnSimulado;
    private String horaSalidaLocalObjetivo;
    private String horaSalidaUtcObjetivo;
    private List<String> enviosAfectados;
    private int cantidadMaletas;
    private String estado;
}
