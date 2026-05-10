package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor

//UMBRALES EXCLUSIVAMENTE PARA REALIZAR PRUEBAS DE HASTA DONDE LLEGA EL ALGORITMO
public class ConfiguracionColapsoDTO {
    private double umbralSinItinerario;
    private double umbralSla;
    private double umbralAeropuerto;
}
