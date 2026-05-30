package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MetricasColapsoDTO {
    private int ciclo;
    private String ventanaInicio;
    private String ventanaFin;
    private int enviosNuevos;
    private int maletasNuevas;
    private int enviosPendientes;
    private int maletasPendientes;
    private int enviosProcesados;
    private int maletasProcesadas;
    private int enviosSinItinerario;
    private int maletasSinItinerario;
    private double porcentajeSinItinerario;
    private int slaIncumplidos;
    private double porcentajeSlaIncumplido;
    private int vuelosSobrecargados;
    private int aeropuertosSaturados;
    private double ocupacionAeropuertoMaxima;
    private double fitnessUltimaSolucion;
    private String motivoColapso;
    private String codigoAeropuertoColapsado;
    private Integer maletasActualesAeropuerto;
    private Integer capacidadAeropuerto;
    private Double porcentajeOcupacionAeropuerto;
    private String causaPrincipal;
    private String fechaHoraColapsoExacta;
    private int enviosSlaIncumplidos;
    private String primerEnvioIncumplido;
    private String deadlinePrimerIncumplido;
    private long retrasoMinutos;
}
