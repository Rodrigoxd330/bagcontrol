package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

import java.util.List;

public record InventarioOperativoAeropuertoDTO(
        String codigoIata,
        int cantidadEnvios,
        int cantidadMaletas,
        List<EnvioInventarioOperativoDTO> envios
) {
}
