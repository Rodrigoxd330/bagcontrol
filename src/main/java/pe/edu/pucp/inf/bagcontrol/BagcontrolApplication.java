package pe.edu.pucp.inf.bagcontrol;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import pe.edu.pucp.inf.bagcontrol.planificacion.service.PlanificadorService;
@SpringBootApplication
public class BagcontrolApplication {

	public static void main(String[] args) {
		SpringApplication.run(BagcontrolApplication.class, args);
	}
	@Bean
	CommandLineRunner runPlanificador(PlanificadorService service) {
		return args -> {
			System.out.println("=== EJECUTANDO GRASP ===");
			service.ejecutarPrueba();
		};
	}
}
