package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SimulacionEstadoDTO {
    private String simulacionId;
    private String estado;
    private boolean pausada;
    private boolean detenida;
    private long saMs;
    private long ultimoLoteEmitido;
    private String algoritmo;
    private int k;
    private String fechaInicio;
    private String fechaCreacion;
    private String tiempoSimuladoActual;
    private String fechaHoraInicioReal;
    private String fechaHoraFinReal;
    private int enviosConEntregaPlanificada;
    private double tiempoPromedioEntregaPlanificadaMinutos;
    private int enviosPendientes;
    private int enviosEntregados;
    private double tiempoPromedioPlanificacionBloqueMs;
    private double tiempoPromedioTotalBloqueMs;
    private long tiempoMaximoPlanificacionBloqueMs;
    private long tiempoMaximoTotalBloqueMs;
    private long sumaSaBloquesMs;
    private long sumaDiferenciaSaTaMs;
    private long bloquesTaMayorSa;
    private java.util.List<String> historialAjustesSa;
}
