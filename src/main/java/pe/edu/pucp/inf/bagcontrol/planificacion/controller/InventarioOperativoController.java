package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioInventarioOperativoDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.InventarioOperativoAeropuertoDTO;

import java.time.ZoneOffset;
import java.util.List;

@RestController
@RequestMapping("/api/operacion-dia/aeropuertos")
@RequiredArgsConstructor
public class InventarioOperativoController {

    private final EnvioDataStore envioDataStore;

    @GetMapping("/inventario")
    public List<InventarioOperativoAeropuertoDTO> obtenerInventario() {
        return envioDataStore.obtenerInventarioOperativoPorAeropuerto().entrySet().stream()
                .map(entry -> crearInventario(entry.getKey(), entry.getValue()))
                .toList();
    }

    @GetMapping("/{codigoIata}/inventario")
    public InventarioOperativoAeropuertoDTO obtenerInventario(@PathVariable String codigoIata) {
        String iata = codigoIata.trim().toUpperCase();
        return crearInventario(iata, envioDataStore.obtenerEnviosOperativosEnAeropuerto(iata));
    }

    private InventarioOperativoAeropuertoDTO crearInventario(String iata, List<Envio> envios) {
        List<EnvioInventarioOperativoDTO> detalle = envios.stream()
                .map(envio -> crearEnvio(envio, iata))
                .toList();
        int maletas = envios.stream().mapToInt(Envio::getCantidadMaletas).sum();
        return new InventarioOperativoAeropuertoDTO(iata, detalle.size(), maletas, detalle);
    }

    private EnvioInventarioOperativoDTO crearEnvio(Envio envio, String aeropuertoActual) {
        return new EnvioInventarioOperativoDTO(
                envio.getIdPedido(), envio.getOrigenIata(), envio.getDestinoIata(),
                envio.getFechaHora().toInstant(ZoneOffset.UTC).toString(),
                envio.getCantidadMaletas(), aeropuertoActual, "EN_ALMACEN"
        );
    }
}
