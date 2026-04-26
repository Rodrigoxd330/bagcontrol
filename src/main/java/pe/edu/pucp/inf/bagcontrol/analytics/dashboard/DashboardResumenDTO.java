package pe.edu.pucp.inf.bagcontrol.analytics.dashboard;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardResumenDTO {
    private double costoPorAnho;
    private int vuelosReprogramados;
    private int enviosEntregadosATiempo;
    private double tiempoPromedioAlmacenaje;
    private double promedioPorcentajeOcupacion;
    private String estado;
}