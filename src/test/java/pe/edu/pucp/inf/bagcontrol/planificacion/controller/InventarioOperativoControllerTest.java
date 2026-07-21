package pe.edu.pucp.inf.bagcontrol.planificacion.controller;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.EnvioDataStore;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class InventarioOperativoControllerTest {

    @Test
    void marcadorYPanelCompartenConteosParaTresAeropuertos() {
        EnvioDataStore store = new EnvioDataStore();
        store.upsert(envio("SPIM-A", "SPIM", 2));
        store.upsert(envio("SPIM-B", "SPIM", 1));
        store.upsert(envio("SABE-A", "SABE", 4));
        store.upsert(envio("EKCH-A", "EKCH", 3));
        InventarioOperativoController controller = new InventarioOperativoController(store);

        var todos = controller.obtenerInventario();
        var spim = controller.obtenerInventario("spim");
        var sabe = controller.obtenerInventario("SABE");
        var ekch = controller.obtenerInventario("EKCH");

        assertThat(todos).containsExactlyInAnyOrder(spim, sabe, ekch);
        assertThat(spim.cantidadEnvios()).isEqualTo(2);
        assertThat(spim.cantidadMaletas()).isEqualTo(3);
        assertThat(spim.envios()).extracting(e -> e.idPedido()).containsExactly("SPIM-A", "SPIM-B");
        assertThat(sabe.cantidadEnvios()).isEqualTo(1);
        assertThat(sabe.cantidadMaletas()).isEqualTo(4);
        assertThat(ekch.cantidadEnvios()).isEqualTo(1);
        assertThat(ekch.cantidadMaletas()).isEqualTo(3);
        assertThat(controller.obtenerInventario("VACIO").cantidadMaletas()).isZero();
    }

    private Envio envio(String id, String origen, int maletas) {
        Envio envio = new Envio();
        envio.setIdPedido(id);
        envio.setOrigenIata(origen);
        envio.setDestinoIata("DEST");
        envio.setCantidadMaletas(maletas);
        envio.setFechaHora(LocalDateTime.of(2026, 7, 21, 17, 0));
        envio.setActivo(true);
        envio.setEsOperacionDia(true);
        return envio;
    }
}
