package pe.edu.pucp.inf.bagcontrol.entidades.envios;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.AeropuertoRepository;

import java.util.Map;
import java.util.stream.Collectors;

@Component
@Order(3)
@RequiredArgsConstructor
public class EnvioLoader implements CommandLineRunner {

    private final EnvioDataStore envioDataStore;
    private final AeropuertoRepository aeropuertoRepository;
    private final EnvioRepository envioRepository;

    @Value("${tasf.b2b.data.envios}")
    private Resource enviosZipResource;

    @Override
    public void run(String... args) throws Exception {
        System.out.println("==================================================");
        System.out.println("3. Construyendo indice liviano de envios desde ZIP...");

        Map<String, Aeropuerto> mapaAeropuertos = aeropuertoRepository.findAll().stream()
                .collect(Collectors.toMap(Aeropuerto::getCodigoIata, a -> a));

        long inicioTiempo = System.currentTimeMillis();
        envioDataStore.inicializarDesdeZip(enviosZipResource, mapaAeropuertos);
        envioRepository.findAll().forEach(envio -> {
            if (envio.isActivo()) {
                envioDataStore.upsert(envio);
            } else {
                envioDataStore.eliminar(envio.getIdPedido());
            }
        });
        long finTiempo = System.currentTimeMillis();

        System.out.println("Indice de envios listo: " + envioDataStore.getTotalEnviosCargados()
                + " envios referenciados sin precargar objetos Envio.");
        System.out.println("Tiempo de indexacion: " + (finTiempo - inicioTiempo) + " ms");
        System.out.println("==================================================\n");
    }
}
