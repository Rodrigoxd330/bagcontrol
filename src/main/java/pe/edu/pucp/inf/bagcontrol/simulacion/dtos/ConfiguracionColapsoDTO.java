package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfiguracionColapsoDTO {
    private int maxDias;
    private int tamanoCicloDias;
    private double umbralSinItinerario;
    private double umbralSla;
    private double umbralAeropuerto;
    private int ciclosPendientesCrecientes;
    private int ciclosSobrecargaVuelo;
}
