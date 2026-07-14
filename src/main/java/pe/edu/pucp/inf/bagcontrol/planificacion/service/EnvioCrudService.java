package pe.edu.pucp.inf.bagcontrol.planificacion.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioRepository;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.NuevoEnvioDTO;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EnvioCrudService {

    private final EnvioRepository envioRepository;
    private final EnvioDataStore envioDataStore;

    @Transactional
    public Envio crear(NuevoEnvioDTO dto) {
        Envio envio = envioDataStore.agregarEnvio(dto, null);
        envioRepository.save(envio);
        return envio;
    }

    @Transactional
    public Optional<Envio> actualizar(String idPedido, NuevoEnvioDTO dto) {
        Optional<Envio> existente = envioDataStore.buscarPorId(idPedido);
        if (existente.isEmpty()) {
            return Optional.empty();
        }

        Envio envio = envioRepository.findById(idPedido).orElseGet(Envio::new);
        envio.setIdPedido(idPedido);
        envio.setOrigenIata(dto.getOrigenIata());
        envio.setDestinoIata(dto.getDestinoIata());
        envio.setCantidadMaletas(dto.getCantidadMaletas());
        envio.setIdCliente(dto.getIdCliente());
        envio.setFechaHora(EnvioDataStore.parsearFechaHoraUtc(dto.getFechaHora()));
        envio.setActivo(true);
        envio.setEsOperacionDia(dto.isEsOperacionDia());
        envioRepository.save(envio);
        envioDataStore.upsert(envio);
        return Optional.of(envio);
    }

    @Transactional
    public boolean eliminar(String idPedido) {
        Optional<Envio> existente = envioDataStore.buscarPorId(idPedido);
        if (existente.isEmpty()) {
            return false;
        }

        Envio tombstone = envioRepository.findById(idPedido).orElseGet(Envio::new);
        tombstone.setIdPedido(idPedido);
        tombstone.setOrigenIata(existente.get().getOrigenIata());
        tombstone.setDestinoIata(existente.get().getDestinoIata());
        tombstone.setFechaHora(existente.get().getFechaHora());
        tombstone.setCantidadMaletas(existente.get().getCantidadMaletas());
        tombstone.setIdCliente(existente.get().getIdCliente());
        tombstone.setActivo(false);
        envioRepository.save(tombstone);
        envioDataStore.eliminar(idPedido);
        return true;
    }
}
