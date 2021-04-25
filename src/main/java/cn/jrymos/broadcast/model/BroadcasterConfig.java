package cn.jrymos.broadcast.model;

import cn.jrymos.broadcast.spi.Server;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "jrymos.mqtt")
@Getter
@Setter
public class BroadcasterConfig {

    public static final String NAME;

    static {
        try {
            NAME = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }

    private List<Server> servers;
    private String broadcastUsername;
    private String broadcastPassword;
    private String clientId;
    private boolean cleanSession = false;
    private long actionResponseTimeout = 1000; // ms
    private int connectionTimeout = 30; // s
    private int threadCores = 1;
    private boolean compress;// true开启压缩广播消息

    public String getBroadcasterClientId() {
        return NAME + ':' + clientId;
    }
}
