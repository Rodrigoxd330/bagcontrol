package pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo;

import org.junit.jupiter.api.Test;
import pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion.FitnessEvaluator;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TabuSearchTest {

    @Test
    void deadlineVencidoFinalizaOrdenadamenteConSolucionFactible() {
        TabuSearch tabuSearch = new TabuSearch(new FitnessEvaluator());
        long inicio = System.currentTimeMillis();

        var solucion = tabuSearch.ejecutar(
                List.of(), Map.of(), List.of(), Map.of(), Set.of(), inicio - 1, 1
        );

        assertThat(solucion).isNotNull();
        assertThat(solucion.getAsignaciones()).isEmpty();
        assertThat(System.currentTimeMillis() - inicio).isLessThan(1_000L);
    }
}
