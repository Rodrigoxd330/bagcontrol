package pe.edu.pucp.inf.bagcontrol.simulacion.motor;

import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import pe.edu.pucp.inf.bagcontrol.simulacion.dtos.LoteEventosDTO;

@Component
@RequiredArgsConstructor
public class WebSocketPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public void publicarLote(String simulacionId, LoteEventosDTO lote) {
        messagingTemplate.convertAndSend("/topic/simulacion/" + simulacionId + "/eventos", lote);
    }
}
