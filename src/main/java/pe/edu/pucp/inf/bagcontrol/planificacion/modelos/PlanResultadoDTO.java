package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.AsignacionPlanDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.PlanResultadoDTO;
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanResultadoDTO {
    private String algoritmo;
    private double fitness;
    private int totalAsignaciones;
    private List<AsignacionPlanDTO> plan;
}