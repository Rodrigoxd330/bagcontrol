package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloFactory;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.EnvioDTO;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.VueloInstanciadoDTO;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ConsultaOperativaService {

    private final EnvioDataStore envioDataStore;
    private final VueloRepository vueloRepository;
    private final VueloFactory vueloFactory;
    private final AeropuertoRepository aeropuertoRepository;

    public List<EnvioDTO> obtenerEnviosEnVentana(LocalDateTime inicio, LocalDateTime fin) {
        var envios = envioDataStore.obtenerEnviosEnVentana(inicio, fin);

        return envios.stream()
                .map(envio -> new EnvioDTO(
                        envio.getIdPedido(),
                        envio.getOrigenIata(),
                        envio.getDestinoIata(),
                        envio.getFechaHora().toInstant(ZoneOffset.UTC).toString(),
                        envio.getCantidadMaletas(),
                        envio.getIdCliente()
                ))
                .toList();
    }

    public List<VueloInstanciadoDTO> obtenerVuelosInstanciados(LocalDate fecha) {
        var vuelosBase = vueloRepository.findAll();
        var aeropuertos = aeropuertoRepository.findAll();
        var instancias = vueloFactory.crearInstanciasDelDia(vuelosBase, fecha, aeropuertos);

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
                .map(this::toEnvioDTO)
                .toList();
    }

    public Page<EnvioDTO> obtenerEnviosPorDiasPaginados(
            LocalDate fechaInicio, int dias,
            String origenIata, String destinoIata, String idCliente, String q,
            Integer maletasMin, Integer maletasMax,
            Pageable pageable
    ) {
        LocalDateTime inicio = fechaInicio.atStartOfDay();
        LocalDateTime fin = fechaInicio.plusDays(dias).atStartOfDay();

        return envioDataStore.obtenerEnviosEnVentanaPaginados(
                inicio, fin, origenIata, destinoIata, idCliente, q,
                maletasMin, maletasMax, pageable
        ).map(this::toEnvioDTO);
    }

    private EnvioDTO toEnvioDTO(pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio envio) {
        return new EnvioDTO(
                envio.getIdPedido(),
                envio.getOrigenIata(),
                envio.getDestinoIata(),
                envio.getFechaHora().toInstant(ZoneOffset.UTC).toString(),
                envio.getCantidadMaletas(),
                envio.getIdCliente()
        );
    }
}
