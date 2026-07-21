package pe.edu.pucp.inf.bagcontrol.planificacion.modelos;

public record EnvioInventarioOperativoDTO(
        String idPedido,
        String origenIata,
        String destinoIata,
        String fechaHora,
        int cantidadMaletas,
        String aeropuertoActual,
        String estado
) {
}
