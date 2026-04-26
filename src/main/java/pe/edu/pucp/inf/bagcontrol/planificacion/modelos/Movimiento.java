package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Movimiento {
    private Envio envio;
    private VueloInstanciado vueloAnterior;
    private VueloInstanciado vueloNuevo;

    public String getIdMovimientoTabu() {
        String idEnvio = envio != null ? envio.getIdPedido() : "SIN_ENVIO";
        String anterior = vueloAnterior != null ? String.valueOf(vueloAnterior.getCodigoBase()) : "SIN_ANTERIOR";
        String nuevo = vueloNuevo != null ? String.valueOf(vueloNuevo.getCodigoBase()) : "SIN_NUEVO";

        return idEnvio + "-" + anterior + "-" + nuevo;
    }
}