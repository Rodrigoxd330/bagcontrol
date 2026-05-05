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

    // GRASP
    private double fitnessGrasp;
    private int asignacionesGrasp;
    private long tiempoGrasp;
    private double vuelosPromedioGrasp;
    private int sinItinerarioGrasp;
    private int excedeSlaGrasp;

    // TABU
    private double fitnessTabu;
    private int asignacionesTabu;
    private long tiempoTabu;
    private double vuelosPromedioTabu;
    private int sinItinerarioTabu;
    private int excedeSlaTabu;

    private String ganador;
}