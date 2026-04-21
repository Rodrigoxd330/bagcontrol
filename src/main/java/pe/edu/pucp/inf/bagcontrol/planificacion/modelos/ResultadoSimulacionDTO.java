package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResultadoSimulacionDTO {
    private String inicio;
    private String fin;
    private int enviosUsados;
    private int vuelosBaseDisponibles;
    private int vuelosInstanciados;
    private int aeropuertosCargados;
    private double fitnessGrasp;
    private int asignacionesGrasp;
    private double fitnessTabu;
    private int asignacionesTabu;
    private String ganador;
}