package pe.edu.pucp.inf.bagcontrol.planificacion.algoritmo;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.entidades.aeropuerto.Aeropuerto;
import pe.edu.pucp.inf.bagcontrol.entidades.envios.Envio;
import pe.edu.pucp.inf.bagcontrol.entidades.vuelo.VueloInstanciado;
import pe.edu.pucp.inf.bagcontrol.planificacion.evaluacion.FitnessEvaluator;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.Movimiento;
import pe.edu.pucp.inf.bagcontrol.planificacion.modelos.SolucionRuta;
import pe.edu.pucp.inf.bagcontrol.planificacion.utils.PlanificadorUtils;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class TabuSearch {

    private final FitnessEvaluator fitnessEvaluator;

    public SolucionRuta ejecutar(List<Envio> envios, List<VueloInstanciado> vuelos, List<Aeropuerto> aeropuertos) {

        int iteraciones = 100;
        int tenure = 10;

        Set<String> listaTabu = new HashSet<>();

        SolucionRuta actual = generarSolucionInicial(envios, vuelos);
        fitnessEvaluator.evaluar(actual, aeropuertos, vuelos);

        SolucionRuta mejor = actual.clonar();

        for (int i = 0; i < iteraciones; i++) {

            List<Movimiento> vecinos = PlanificadorUtils.generarVecindario(actual, vuelos);
            vecinos = vecinos.subList(0, Math.min(50, vecinos.size()));

            SolucionRuta mejorVecino = null;
            Movimiento mejorMovimiento = null;

            for (Movimiento mov : vecinos) {

                String id = mov.getIdMovimientoTabu();

                SolucionRuta candidata = actual.clonar();
                candidata.aplicarMovimientoDefinitivo(mov);

                fitnessEvaluator.evaluar(candidata, aeropuertos, vuelos);

                if (listaTabu.contains(id) && candidata.getFitness() >= mejor.getFitness()) {
                    continue;
                }

                if (mejorVecino == null || candidata.getFitness() < mejorVecino.getFitness()) {
                    mejorVecino = candidata;
                    mejorMovimiento = mov;
                }
            }

            if (mejorVecino == null) {
                break;
            }

            actual = mejorVecino;

            if (actual.getFitness() < mejor.getFitness()) {
                mejor = actual.clonar();
            }

            if (mejorMovimiento != null) {
                listaTabu.add(mejorMovimiento.getIdMovimientoTabu());

                if (listaTabu.size() > tenure) {
                    Iterator<String> it = listaTabu.iterator();
                    it.next();
                    it.remove();
                }
            }
        }

        return mejor;
    }

    private SolucionRuta generarSolucionInicial(List<Envio> envios, List<VueloInstanciado> vuelos) {
        SolucionRuta solucion = new SolucionRuta();
        Random random = new Random();

        for (Envio envio : envios) {
            List<VueloInstanciado> posibles = PlanificadorUtils.buscarVuelosPosiblesParaEnvio(envio, vuelos);

            if (posibles.isEmpty()) {
                solucion.agregarAsignacion(envio, null);
            } else {
                solucion.agregarAsignacion(envio, posibles.get(random.nextInt(posibles.size())));
            }
        }

        return solucion;
    }
}