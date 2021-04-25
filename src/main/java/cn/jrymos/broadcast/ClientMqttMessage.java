package cn.jrymos.broadcast;

import lombok.Data;
import org.eclipse.paho.client.mqttv3.MqttMessage;

@Data
public class ClientMqttMessage extends MqttMessage {
    private String topic;

    public ClientMqttMessage(String topic, byte[] payload) {
        super(payload);
        this.topic = topic;
    }

    public static ClientMqttMessage of(String topic, byte[] bytes) {
        return new ClientMqttMessage(topic, bytes);
    }
}
