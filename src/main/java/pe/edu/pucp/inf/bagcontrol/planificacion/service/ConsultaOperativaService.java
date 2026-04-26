package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloFactory;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.VueloInstanciadoDTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConsultaOperativaService {

    private final EnvioDataStore envioDataStore;
    private final VueloRepository vueloRepository;
    private final VueloFactory vueloFactory;

    public List<EnvioDTO> obtenerEnviosEnVentana(LocalDateTime inicio, LocalDateTime fin) {
        var envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);

        return envios.stream()
                .map(envio -> new EnvioDTO(
                        envio.getIdPedido(),
                        envio.getOrigenIata(),
                        envio.getDestinoIata(),
                        envio.getFechaHora().toString(),
                        envio.getCantidadMaletas(),
                        envio.getIdCliente()
                ))
                .toList();
    }

    public List<VueloInstanciadoDTO> obtenerVuelosInstanciados(LocalDate fecha) {
        var vuelosBase = vueloRepository.findAll();
        var instancias = vueloFactory.crearInstanciasDelDia(vuelosBase, fecha);

        return instancias.stream()
                .map(v -> new VueloInstanciadoDTO(
                        v.getCodigoBase(),
                        v.getOrigenIata(),
                        v.getDestinoIata(),
                        v.getFechaHoraSalida().toString(),
                        v.getFechaHoraLlegada().toString(),
                        v.getCapacidadMax(),
                        v.getOcupacionActual(),
                        v.isEstaCancelado()
                ))
                .toList();
    }
    public List<EnvioDTO> obtenerEnviosPorDias(LocalDate fechaInicio, int dias) {
        LocalDateTime inicio = fechaInicio.atStartOfDay();
        LocalDateTime fin = fechaInicio.plusDays(dias).atStartOfDay();

        var envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);

        return envios.stream()
                .map(envio -> new EnvioDTO(
                        envio.getIdPedido(),
                        envio.getOrigenIata(),
                        envio.getDestinoIata(),
                        envio.getFechaHora().toString(),
                        envio.getCantidadMaletas(),
                        envio.getIdCliente()
                ))
                .toList();
    }
}