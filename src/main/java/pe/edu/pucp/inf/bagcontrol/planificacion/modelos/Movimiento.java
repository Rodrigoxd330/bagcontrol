package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.Vuelo;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Movimiento {
    private Envio envio;
    private Vuelo vueloAnterior;
    private Vuelo vueloNuevo;

    public String getIdMovimientoTabu() {
        String anterior = vueloAnterior != null ? vueloAnterior.getCodigo().toString() : "null";
        String nuevo = vueloNuevo != null ? vueloNuevo.getCodigo().toString() : "null";
        return envio.getIdPedido() + ":" + anterior + "->" + nuevo;
    }
}