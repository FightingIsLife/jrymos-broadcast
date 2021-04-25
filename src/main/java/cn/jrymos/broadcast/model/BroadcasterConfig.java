package cn.jrymos.broadcast.model;

import cn.jrymos.broadcast.spi.Server;
import com.google.common.collect.ImmutableList;
import lombok.Getter;
import lombok.Setter;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

@Component
@Configuration("jrymos.mqtt")
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

    private List<Server> servers = ImmutableList.of(new Server("127.0.0.1", 8943));
    private String broadcastUsername = "jrymos";
    private String broadcastPassword = "jrymos";
    private String clientId = "jrymos";
    private boolean cleanSession = false;
    private long actionResponseTimeout = 1000; // ms
    private int connectionTimeout = 30; // s
    private int threadCores = 1;
    private boolean compress = true;// true开启压缩广播消息

    public String getBroadcasterClientId() {
        return NAME + ':' + clientId;
    }
}
