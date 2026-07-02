package pe.edu.pucp.inf.bagcontrol.simulacion.dtos;

public record SimulacionActivaDTO(
        String simulacionId,
        String websocketTopic,
        String modo,
        String estado,
        int k,
        String fechaInicio,
        String fechaCreacion,
        String propietarioEmail,
        String propietarioNombre
) {
}
